package com.example.lms.service.rag;

import com.example.rag.fusion.WeightedRRF;
import com.example.retrieval.KAllocator;
import com.example.moe.GateVector;
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
import com.example.lms.prompt.PromptContext;
import java.lang.reflect.Method;
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

import dev.langchain4j.rag.query.Metadata; // [HARDENING] 1.0.x Query 메타 타입
import java.util.Map; // [HARDENING]
// imports
import com.example.lms.service.rag.rerank.ElementConstraintScorer; //  신규 재랭커

import com.example.lms.service.config.HyperparameterService; // ★ NEW
import com.example.lms.search.TraceStore;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import org.springframework.beans.factory.annotation.Qualifier; // - FIX: 다중 빈 모호성 해결용 @Qualifier
import jakarta.annotation.PostConstruct; // + 개선: 프로퍼티 기반 백엔드 선택 지원

@Component("vectorRetriever")
@RequiredArgsConstructor
public class HybridRetriever implements ContentRetriever {

    @Value("${selfask.enabled:true}")
    private boolean selfAskEnabled;

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    // fields (다른 final 필드들과 같은 위치)
    private final LightWeightRanker lightWeightRanker;
    // Gate controlling invocation of the expensive cross-encoder reranker.
    private final com.example.lms.service.rag.rerank.RerankGate rerankGate;
    private final AuthorityScorer authorityScorer;
    private static final double GAME_SIM_THRESHOLD = 0.3;

    // 메타키 (필요 시 Query.metadata에 실어 전달)
    private static final String META_ALLOWED_DOMAINS = "allowedDomains"; // List<String>
    private static final String META_MAX_PARALLEL = "maxParallel"; // Integer
    private static final String META_DEDUPE_KEY = "dedupeKey"; // "text" | "url" | "hash"
    private static final String META_OFFICIAL_DOMAINS = "officialDomains"; // List<String>

    @Value("${rag.search.top-k:5}")
    private int topK;

    // 체인 & 융합기
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
    private final HyperparameterService hp; // ★ NEW: 동적 가중치 로더
    private final ElementConstraintScorer elementConstraintScorer; // ★ NEW: 원소 제약 재랭커
    private final QueryTransformer queryTransformer; // ★ NEW: 상태 기반 질의 생성
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

    // 🔴 NEW: 교차엔코더 기반 재정렬(없으면 스킵)
    @Autowired(required = false)
    @Qualifier("noopCrossEncoderReranker") // - FIX: 빈 3개(onnx/noop/embedding) 충돌 → 기본 noop로 명시
    private com.example.lms.service.rag.rerank.CrossEncoderReranker crossEncoderReranker;

    @Autowired(required = false)
    private Map<String, com.example.lms.service.rag.rerank.CrossEncoderReranker> rerankers = java.util.Collections
            .emptyMap(); // + 개선: 런타임에 백엔드 스위칭 가능

    @Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @Value("${abandonware.reranker.backend:noop}")
    private String rerankerBackend; // + 개선: 프로퍼티로 onnx/embedding/noop 선택

    // 리트리버들
    private final SelfAskWebSearchRetriever selfAskRetriever;
    private final AnalyzeWebSearchRetriever analyzeRetriever;
    private final WebSearchRetriever webSearchRetriever;
    private final QueryComplexityGate gate;

    // (옵션) 타사 검색기 - 있으면 부족분 보강에 사용
    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("tavilyWebSearchRetriever")
    private ContentRetriever tavilyWebSearchRetriever;
    // RAG/임베딩
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

    @Value("${hybrid.min-relatedness:0.01}") // 관련도 필터 컷오프
    private double minRelatedness;
    // ★ 융합 모드: rrf(기본) | softmax
    @Value("${retrieval.fusion.mode:rrf}")
    private String fusionMode;
    // ★ softmax 융합 온도
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
    private boolean useMlCorrection; // ★ NEW: ML 보정 온/오프

    /** 검색 일관성 → 암묵 강화 임계치 */
    @Value("${retrieval.consistency.threshold:0.8}")
    private double consistencyThreshold;

    @PostConstruct
    private void selectRerankerByProperty() {
        // application.yml 의 abandonware.reranker.backend 값에 따라 백엔드 자동 선택
        try {
            if (rerankerBackend == null || rerankerBackend.isBlank()) {
                return;
            }

            String backend = rerankerBackend.trim().toLowerCase();
            String key;
            switch (backend) {
                case "onnx-runtime":
                case "onnx":
                    key = "onnxCrossEncoderReranker";
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

            // Backward compatibility: if someone still registers "crossEncoderReranker"
            // only.
            if (chosen == null && key.endsWith("CrossEncoderReranker")) {
                chosen = rerankers.get("crossEncoderReranker");
            }

            if (chosen != null) {
                this.crossEncoderReranker = chosen;
                log.info("[Hybrid] CrossEncoderReranker set via property backend='{}' bean='{}'", rerankerBackend, key);
            } else if (!rerankers.isEmpty()) {
                // last-resort: pick any available implementation to keep the pipeline alive
                com.example.lms.service.rag.rerank.CrossEncoderReranker fallback = rerankers.values().iterator().next();
                this.crossEncoderReranker = fallback;
                log.warn("[Hybrid] Reranker bean '{}' not found for backend='{}' → using '{}'",
                        key, rerankerBackend, fallback.getClass().getSimpleName());
            } else {
                log.info("[Hybrid] No reranker beans registered; keeping injected default: {}",
                        (crossEncoderReranker != null ? crossEncoderReranker.getClass().getSimpleName() : "none"));
            }
        } catch (Exception ignore) {
            // 안전: 선택 실패해도 기본 주입 유지
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
            return new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker();
        }

        String backend = backendOverride;
        if (backend == null || backend.isBlank())
            backend = rerankerBackend;
        backend = backend == null ? "" : backend.trim().toLowerCase(java.util.Locale.ROOT);

        boolean onnxAllowed = onnxEnabledOverride != Boolean.FALSE && metaBool(metaMap, "onnx.enabled", true);
        boolean onnxBreakerOpen = false;
        try {
            onnxBreakerOpen = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.RERANK_ONNX));
        } catch (Exception ignore) {
            // fail-soft
        }

        boolean hasOnnx = rerankers.containsKey("onnxCrossEncoderReranker");
        boolean hasEmbedding = rerankers.containsKey("embeddingCrossEncoderReranker");
        boolean hasNoop = rerankers.containsKey("noopCrossEncoderReranker");

        boolean onnxUsable = crossEncoderEnabled && onnxAllowed && hasOnnx && !onnxBreakerOpen;

        String key;
        switch (backend) {
            case "", "auto" -> {
                if (!crossEncoderEnabled) {
                    key = "noopCrossEncoderReranker";
                } else if (onnxUsable) {
                    key = "onnxCrossEncoderReranker";
                } else if (hasEmbedding) {
                    key = "embeddingCrossEncoderReranker";
                } else {
                    key = hasNoop ? "noopCrossEncoderReranker" : rerankers.keySet().iterator().next();
                }
            }
            case "onnx-runtime", "onnx" -> {
                if (!crossEncoderEnabled) {
                    key = "noopCrossEncoderReranker";
                } else if (onnxUsable) {
                    key = "onnxCrossEncoderReranker";
                } else {
                    key = hasEmbedding ? "embeddingCrossEncoderReranker"
                            : (hasNoop ? "noopCrossEncoderReranker" : rerankers.keySet().iterator().next());
                }
            }
            case "embedding-model", "embedding", "bi-encoder", "biencoder" -> key = "embeddingCrossEncoderReranker";
            case "noop", "none", "disabled" -> key = "noopCrossEncoderReranker";
            default -> key = "embeddingCrossEncoderReranker";
        }

        com.example.lms.service.rag.rerank.CrossEncoderReranker r = rerankers.get(key);
        if (r != null)
            return r;

        // final fallback order: embedding → noop → any
        if (hasEmbedding)
            return rerankers.get("embeddingCrossEncoderReranker");
        if (hasNoop)
            return rerankers.get("noopCrossEncoderReranker");
        return rerankers.values().iterator().next();
    }

    @Override
    public List<Content> retrieve(Query query) {

        // 0) 메타 파싱
        String sessionKey = Optional.ofNullable(query)
                .map(Query::metadata)
                .map(HybridRetriever::toMap)
                .map(md -> md.get(LangChainRAGService.META_SID))
                .map(Object::toString)
                .orElse(null);

        Map<String, Object> md = Optional.ofNullable(query)
                .map(Query::metadata)
                .map(HybridRetriever::toMap)
                .orElse(Map.of());

        @SuppressWarnings("unchecked")
        List<String> allowedDomains = (List<String>) md.getOrDefault(META_ALLOWED_DOMAINS, List.of());
        @SuppressWarnings("unchecked")
        List<String> officialDomains = (List<String>) md.getOrDefault(META_OFFICIAL_DOMAINS, allowedDomains);

        // 메타에 들어온 병렬 상한(없으면 기본설정 사용)
        int maxParallelOverride = Optional.ofNullable((Integer) md.get(META_MAX_PARALLEL)).orElse(this.maxParallel);
        String dedupeKey = (String) md.getOrDefault(META_DEDUPE_KEY, "text");

        LinkedHashSet<Content> mergedContents = new LinkedHashSet<>();

        // 1) 난이도 게이팅
        final String q = (query != null && query.text() != null) ? query.text().strip() : "";

        // ── pre-fuse candidate cap (retrieveAll과 동일한 의도: 후보 생성량 제한) ────────────────
        int prefuseCap = -1;
        try {
            int keepN = metaInt(md, "rerank.topK", -1);
            if (keepN <= 0)
                keepN = metaInt(md, "rerank_top_k", -1);
            if (keepN <= 0)
                keepN = metaInt(md, "rerankTopK", -1);

            int candidateCap = metaInt(md, "rerank.ce.topK", -1);
            if (candidateCap <= 0)
                candidateCap = metaInt(md, "rerank.ceTopK", -1);
            if (candidateCap <= 0)
                candidateCap = metaInt(md, "rerank_ce_top_k", -1);
            if (candidateCap <= 0)
                candidateCap = metaInt(md, "rerankCeTopK", -1);
            if (candidateCap <= 0)
                candidateCap = metaInt(md, "rerank.candidateK", -1);
            if (candidateCap <= 0)
                candidateCap = metaInt(md, "rerank.candidate_k", -1);
            if (candidateCap <= 0)
                candidateCap = metaInt(md, "rerank_candidate_k", -1);
            if (candidateCap <= 0)
                candidateCap = metaInt(md, "rerankCandidateK", -1);

            if (candidateCap <= 0 && keepN > 0) {
                candidateCap = Math.max(keepN * 2, Math.max(keepN, topK));
            }

            if (candidateCap > 0) {
                prefuseCap = (keepN > 0) ? Math.max(candidateCap, keepN) : candidateCap;
            }
        } catch (Exception ignore) {
            // fail-soft
        }

        final boolean applyPrefuseCap = prefuseCap > 0;
        final int maxCandidates = applyPrefuseCap ? Math.max(1, prefuseCap) : Integer.MAX_VALUE;
        final int minNeed = applyPrefuseCap ? Math.max(1, Math.min(topK, maxCandidates)) : topK;

        if (applyPrefuseCap) {
            try {
                TraceStore.put("rerank.prefuse.cap", maxCandidates);
                TraceStore.put("rerank.prefuse.minNeed", minNeed);
            } catch (Exception ignore) {
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
            detectedDomain = "GENERAL";
        }
        final String chosenIndex = chooseIndex(detectedDomain);

        // ── 조건부 파이프라인: 교육/훈련 Intent 기반 벡터 검색 모드 ──────────────────
        try {
            boolean isEducationIntent = "EDU".equalsIgnoreCase(detectedDomain)
                    || "EDUCATION".equalsIgnoreCase(detectedDomain);

            // Query metadata에서 intent 힌트 읽기 (선행 Preprocessor가 채웠다고 가정)
            String intentFromMeta = null;
            try {
                if (md != null) {
                    Object raw = md.get("intent");
                    if (raw instanceof String s) {
                        intentFromMeta = s;
                    }
                }
            } catch (Exception ignore) {
                // 메타 파싱 장애는 무시하고 기본 도메인만 사용
            }
            if ("education".equalsIgnoreCase(intentFromMeta)
                    || "training".equalsIgnoreCase(intentFromMeta)) {
                isEducationIntent = true;
            }

            if (isEducationIntent) {
                log.debug("[Hybrid] Education intent → vector-only retrieval (index={})", chosenIndex);
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
                    // if ranking fails, maintain original order
                }
                // limit to topK
                List<Content> topList = deduped.size() > topK ? deduped.subList(0, topK) : deduped;
                // finalise and return
                return finalizeResults(new ArrayList<>(topList), dedupeKey, officialDomains, q, md);
            }
        } catch (Exception ignore) {
            // on error continue with default behaviour
        }

        QueryComplexityGate.Level level = gate.assess(q);
        log.debug("[Hybrid] level={} q='{}'", level, q);

        switch (level) {
            case SIMPLE -> {
                // 단순 질의: WebSearchRetriever 먼저, 없으면 Vector로 fallback.
                List<Content> webResults = Collections.emptyList();
                try {
                    webResults = webSearchRetriever.retrieve(query);
                } catch (Exception e) {
                    log.warn("[HybridRetriever] Web search failed: {}", e.toString());
                }

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
                        log.debug("[Hybrid] Tavily fallback skipped: {}", e.toString());
                    }
                }
            }
            case AMBIGUOUS -> {
                addCapped(mergedContents, analyzeRetriever.retrieve(query), maxCandidates);

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates) {
                    addCapped(mergedContents, webSearchRetriever.retrieve(query), maxCandidates);
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
                        log.debug("[Hybrid] Tavily fallback skipped: {}", e.toString());
                    }
                }
            }
            case COMPLEX -> {
                addCapped(mergedContents, selfAskRetriever.retrieve(query), maxCandidates);

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates) {
                    addCapped(mergedContents, analyzeRetriever.retrieve(query), maxCandidates);
                }

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates) {
                    addCapped(mergedContents, webSearchRetriever.retrieve(query), maxCandidates);
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
                        log.debug("[Hybrid] Tavily fallback skipped: {}", e.toString());
                    }
                }
            }
        }

        // 최종 정제
        List<Content> out = finalizeResults(new ArrayList<>(mergedContents), dedupeKey, officialDomains, q, md);

        // ─ 암묵 피드백(검색 일관성) 반영
        try {
            maybeRecordImplicitConsistency(q, out, officialDomains);
        } catch (Exception ignore) {
        }

        return out;
    }

    private static void addCapped(java.util.Set<Content> dst, java.util.List<Content> src, int cap) {
        if (dst == null || src == null || src.isEmpty()) {
            return;
        }
        if (cap <= 0 || cap == Integer.MAX_VALUE) {
            dst.addAll(src);
            return;
        }
        for (Content c : src) {
            if (c == null) {
                continue;
            }
            dst.add(c);
            if (dst.size() >= cap) {
                break;
            }
        }
    }

    /**
     * 벡터 검색 결과에서 오염 청크 필터링 (fail-soft).
     *
     * <ul>
     * <li>메타 없으면 통과 (레거시 호환)</li>
     * <li>ASSISTANT + verified=false 는 차단</li>
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
        }

        if (md == null || md.isEmpty()) {
            return true; // 레거시 데이터는 통과
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

    private static boolean containsAny(String text, String[] cues) {
        if (text == null)
            return false;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        for (String c : cues)
            if (t.contains(c))
                return true;
        return false;
    }

    private static final String[] SYNERGY_CUES = { "시너지", "조합", "궁합", "함께", "어울", "콤보" };

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
            String url = extractUrl(text);
            boolean both = text != null
                    && text.toLowerCase(java.util.Locale.ROOT).contains(subject.toLowerCase(java.util.Locale.ROOT))
                    && text.toLowerCase(java.util.Locale.ROOT).contains(partner.toLowerCase(java.util.Locale.ROOT));
            if (both) {
                total++;
                double w = containsAny(text, SYNERGY_CUES) ? 1.0 : 0.6; // 시너지 단서 보너스
                if (isOfficial(url, officialDomains))
                    w += 0.1; // 공식 도메인 보너스
                if (w >= 0.9)
                    hit++; // 강한 지지로 카운트
            }
        }
        if (total <= 0)
            return;
        double consistency = hit / (double) total;
        scoring.applyImplicitPositive(domain, subject, partner, consistency);
        // If the consistency score is high enough, attempt to persist the path for
        // future alignment.
        try {
            if (pathFormation != null) {
                pathFormation.maybeFormPath(subject + "->" + partner, consistency);
            }
        } catch (Throwable ignore) {
            // path reinforcement failures should not break retrieval
        }
    }

    /**
     * Progressive retrieval:
     * 1) Local RAG 우선 → 품질 충분 시 조기 종료
     * 2) 미흡 시 Self-Ask(1~2개)로 정제된 웹 검색만 수행
     */
    @Deprecated // ← 폭포수 검색 비활성화 경로(남겨두되 호출은 남김)
    public List<Content> retrieveProgressive(String question, String sessionKey, int limit) {
        if (question == null || question.isBlank()) {
            return List.of(Content.from("[빈 질의]"));
        }
        final int top = Math.max(1, limit);

        try {
            // 1) 로컬 RAG 우선
            // Detect the domain of the question and select the appropriate pinecone index.
            String domain;
            try {
                domain = (domainDetector != null) ? domainDetector.detect(question) : "GENERAL";
            } catch (Exception ignore) {
                domain = "GENERAL";
            }
            String idx = chooseIndex(domain);
            ContentRetriever pine = ragService.asContentRetriever(idx);
            // [HARDENING] build query with metadata for session isolation
            String sidForQuery = (sessionKey == null || sessionKey.isBlank()) ? "__TRANSIENT__" : sessionKey;
            dev.langchain4j.rag.query.Query qObj = QueryUtils.buildQuery(question, sidForQuery, null);
            Map<String, Object> md0 = toMap(qObj.metadata());

            // ── pre-fuse candidate cap (retrieveAll/retrieve와 동일한 의도: 후보 생성량 제한)
            // ───────────
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
                // fail-soft
            }
            final boolean applyPrefuseCap = prefuseCap > 0;

            List<Content> local = pine.retrieve(qObj);

            if (applyPrefuseCap && local != null && local.size() > fuseLimit) {
                local = new java.util.ArrayList<>(local.subList(0, fuseLimit));
            }

            if (qualityEvaluator != null
                    && qualityEvaluator.isSufficient(question, local, qualityMinDocs, qualityMinScore)) {
                log.info("[Hybrid] Local RAG sufficient → skip web (sid={}, q='{}')", sessionKey, question);
                List<Content> out = finalizeResults(new ArrayList<>(local), "text", java.util.Collections.emptyList(),
                        question, md0);
                return out.size() > top ? out.subList(0, top) : out;
            }

            // 2) Self-Ask로 1~2개 핵심 질의 생성 → 위생 필터
            List<String> planned;
            if (!selfAskEnabled || selfAskPlanner == null) {
                planned = List.of(question);
            } else {
                try {
                    planned = selfAskPlanner.plan(question, 2);
                } catch (Exception e) {
                    log.warn("[Hybrid] SelfAskPlanner 실패(sid={}, q='{}'): {} → fallback to raw query",
                            sessionKey, question, e.toString());
                    planned = List.of(question);
                }
            }
            List<String> queries = QueryHygieneFilter.sanitize(planned, 2, 0.80);

            if (queries.isEmpty())
                queries = List.of(question);
            if (queries.isEmpty())
                queries = List.of(question);

            // 3) 필요한 쿼리만 순차 처리 → 융합
            List<List<Content>> buckets = new ArrayList<>();
            for (String q : queries) {
                List<Content> acc = new ArrayList<>();
                try {
                    // [HARDENING] build a query with session metadata using QueryUtils
                    java.util.Map<String, Object> mdSub = new java.util.HashMap<>();
                    if (md0 != null && !md0.isEmpty()) {
                        mdSub.putAll(md0);
                    }

                    // pre-fuse 단계에서 lane topK도 cap (retriever 비용 절감)
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
                    log.warn("[Hybrid] handler 실패: {}", e.toString());
                }
                buckets.add(acc);
            }

            // 융합 및 최종 정제 후 상위 top 반환
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
            List<Content> combined = new ArrayList<>(local); // 'local'은 이 메소드 상단에서 이미 정의되어 있어야 합니다.
            combined.addAll(fused);

            List<Content> out = finalizeResults(combined, "text", java.util.Collections.emptyList(), question, md0);
            return out.size() > top ? out.subList(0, top) : out;

        } catch (Exception e) {
            log.error("[Hybrid] retrieveProgressive 실패(sid={}, q='{}')", sessionKey, question, e);
            return List.of(Content.from("[검색 오류]"));
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
        if (question == null || question.isBlank()) {
            return java.util.List.of(Content.from("[빈 질의]"));
        }
        final int top = Math.max(1, limit);

        try {
            // 1) Local RAG first
            String domain;
            try {
                domain = (domainDetector != null) ? domainDetector.detect(question) : "GENERAL";
            } catch (Exception ignore) {
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

            // ── pre-fuse candidate cap (retrieveAll/retrieve와 동일한 의도: 후보 생성량 제한)
            // ───────────
            // rerank_ce_top_k / rerank_candidate_k는 원래 CE 입력 후보 컷 용도지만,
            // progressive 경로에서도 "fuse 이전 후보 생성량"을 같이 제한하면 비용/지연을 더 줄일 수 있다.
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
                // fail-soft
            }
            final boolean applyPrefuseCap = prefuseCap > 0;

            // pre-fuse 단계에서 lane topK도 cap (retriever 비용 절감)
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
                    // fail-soft
                }
            }

            dev.langchain4j.data.document.Metadata md = dev.langchain4j.data.document.Metadata.from(mdMap);
            dev.langchain4j.rag.query.Query qObj;
            try {
                qObj = new dev.langchain4j.rag.query.Query(question, md);
            } catch (Throwable t) {
                qObj = dev.langchain4j.rag.query.Query.builder().text(question).metadata(md).build();
            }
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
                    log.warn("[Hybrid] SelfAskPlanner 실패(sid={}, q='{}'): {} → fallback to raw query",
                            sessionKey, question, e.toString());
                    planned = java.util.List.of(question);
                }
            }
            java.util.List<String> queries = com.example.lms.search.QueryHygieneFilter.sanitize(planned, 2, 0.80);
            if (queries.isEmpty())
                queries = java.util.List.of(question);
            if (queries.isEmpty())
                queries = java.util.List.of(question);
            java.util.List<Integer> kSchedule = toIntList(mdMap.get("kSchedule"));
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

                    // pre-fuse 단계에서 lane topK도 cap (retriever 비용 절감)
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
                    dev.langchain4j.data.document.Metadata subMdObj = dev.langchain4j.data.document.Metadata
                            .from(subMd);
                    dev.langchain4j.rag.query.Query subQ;
                    try {
                        subQ = new dev.langchain4j.rag.query.Query(q, subMdObj);
                    } catch (Throwable t) {
                        subQ = dev.langchain4j.rag.query.Query.builder().text(q).metadata(subMdObj).build();
                    }
                    handlerChain.handle(subQ, acc);

                    if (applyPrefuseCap && acc.size() > fuseLimit) {
                        acc = new java.util.ArrayList<>(acc.subList(0, fuseLimit));
                    }
                } catch (Exception e) {
                    log.warn("[Hybrid] handler 실패: {}", e.toString());
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
            log.error("[Hybrid] retrieveProgressive 실패(sid={}, q='{}')", sessionKey, question, e);
            return java.util.List.of(Content.from("[검색 오류]"));
        }
    }

    /**
     * 다중 쿼리 병렬 검색 + RRF 융합
     */
    public List<Content> retrieveAll(List<String> queries, int limit) {
        return retrieveAll(queries, limit, "__TRANSIENT__", null);
    }

    /**
     * 요청 단위 힌트(plate/topK/budget 등)를 메타데이터로 전달하는 오버로드.
     *
     * <p>
     * 중요: 과거 코드가 __TRANSIENT__로만 Query를 만들면서 plate가 실제 검색 파라미터에
     * 반영되지 않는 문제가 있었다. 이 메서드는 metaHints를 Query metadata로 주입해
     * WebSearch/SelfAsk/Analyze 단계가 실제로 읽을 수 있게 한다.
     */
    public List<Content> retrieveAll(List<String> queries, int limit, Object sessionKey,
            java.util.Map<String, Object> metaHints) {
        if (queries == null || queries.isEmpty()) {
            return java.util.List.of();
        }

        final Object sid = (sessionKey != null ? sessionKey : "__TRANSIENT__");

        // pre-fuse candidate cap:
        // rerank_ce_top_k / rerank_candidate_k는 원래 CE 입력 후보 컷 용도지만,
        // fuse 이전 "후보 생성(limit)"도 같이 제한하면 비용/지연을 더 줄일 수 있다.
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

                // keepN만 있는 경우: 기본으로 2x keepN 만큼 후보를 만들고 fuse 하도록 유도
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
            // fail-soft: keep original limit
        }

        final int fuseLimit = Math.max(1, effectiveLimit);
        final boolean applyPrefuseCap = prefuseCap > 0;

        try {
            java.util.List<java.util.List<Content>> results;
            if (debugSequential) {
                log.warn("[Hybrid] debug.sequential=true → handlerChain 순차 실행");
                results = new java.util.ArrayList<>();
                for (String q : queries) {
                    java.util.List<Content> acc = new java.util.ArrayList<>();
                    try {
                        java.util.Map<String, Object> md = new java.util.HashMap<>();
                        if (metaHints != null && !metaHints.isEmpty())
                            md.putAll(metaHints);

                        // pre-fuse 단계에서 lane topK도 cap (retriever 비용 절감)
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
                        dev.langchain4j.rag.query.Query subQ = QueryUtils.buildQuery(q, sid, null, md);
                        handlerChain.handle(subQ, acc);

                        if (applyPrefuseCap && acc.size() > fuseLimit) {
                            acc = new java.util.ArrayList<>(acc.subList(0, fuseLimit));
                        }
                    } catch (Exception e) {
                        log.warn("[Hybrid] handler 실패: {}", q, e);
                    }
                    results.add(acc);
                }
            } else {
                // 기본: 제한 병렬 실행 (공용 풀 사용 금지)
                //
                // UAW: 병렬 합성부에서 MDC/GuardContext/TraceStore 전파가 끊기면
                // handlerChain이 "pass만" 반복하다가 결과가 0으로 수렴하는 케이스가 있다.
                // ContextPropagation으로 task를 감싸 요청 단위 컨텍스트를 유지한다.
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                        Math.max(1, this.maxParallel));
                try {
                    java.util.List<java.util.concurrent.CompletableFuture<java.util.List<Content>>> futures =
                            new java.util.ArrayList<>(queries.size());

                    for (String q : queries) {
                        futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                                com.example.lms.infra.exec.ContextPropagation.wrapSupplier(() -> {
                                    java.util.List<Content> acc = new java.util.ArrayList<>();
                                    try {
                                        java.util.Map<String, Object> md = new java.util.HashMap<>();
                                        if (metaHints != null && !metaHints.isEmpty())
                                            md.putAll(metaHints);

                                        // pre-fuse 단계에서 lane topK도 cap (retriever 비용 절감)
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
                                        dev.langchain4j.rag.query.Query subQ = QueryUtils.buildQuery(q, sid, null, md);
                                        handlerChain.handle(subQ, acc);
                                    } catch (Exception e) {
                                        log.warn("[Hybrid] handler 실패: {}", q, e);
                                    }

                                    if (applyPrefuseCap && acc.size() > fuseLimit) {
                                        return new java.util.ArrayList<>(acc.subList(0, fuseLimit));
                                    }
                                    return acc;
                                }), pool));
                    }

                    results = futures.stream()
                            .map(f -> {
                                try {
                                    return f.join();
                                } catch (Exception e) {
                                    return java.util.List.<Content>of();
                                }
                            })
                            .toList();
                } finally {
                    pool.shutdown();
                }
            }

            // RRF or Softmax 융합 후 상위 limit 반환
            boolean useSoftmax = "softmax".equalsIgnoreCase(fusionMode)
                    && ("minmax".equalsIgnoreCase(softmaxCalibration)
                            || "isotonic".equalsIgnoreCase(softmaxCalibration));
            if (useSoftmax) {
                String q0 = queries.get(0); // representative query (approximation)
                return fuseWithSoftmax(results, fuseLimit, q0);
            }

            boolean useWeighted = weightedFuser != null &&
                    ("weighted-rrf".equalsIgnoreCase(fusionMode) ||
                            "rrf-weighted".equalsIgnoreCase(fusionMode) ||
                            "weighted".equalsIgnoreCase(fusionMode));
            if (useWeighted) {
                return weightedFuser.fuse(results, fuseLimit);
            }
            return fuser.fuse(results, fuseLimit);
        } catch (Exception e) {
            log.error("[Hybrid] retrieveAll 실패", e);
            return java.util.List.of(Content.from("[검색 오류]"));
        }
    } // retrieveAll 끝

    // ─────────────────────────────────────────────
    // 상태 기반 검색: CognitiveState/PromptContext를 반영해 쿼리 확장 → 병렬 검색
    // ─────────────────────────────────────────────
    public List<Content> retrieveStateDriven(PromptContext ctx, int limit) {
        String userQ = Optional.ofNullable(ctx.userQuery()).orElse("");
        String lastA = ctx.lastAssistantAnswer();
        String subject = ctx.subject();
        // QueryTransformer의 확장 API 활용
        List<String> queries = queryTransformer.transformEnhanced(userQ, lastA, subject);
        if (queries.isEmpty())
            queries = List.of(userQ);
        return retrieveAll(queries, Math.max(1, limit));
    }

    // ───────────────────────────── 헬퍼들 ─────────────────────────────

    /**
     * (옵션) 코사인 유사도 - 필요 시 사용
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
            return 0d;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object meta) {
        if (meta == null)
            return Map.of();
        // LangChain4j 1.0.x: rag.query.Metadata → chatMemoryId 및 asMap 지원
        if (meta instanceof dev.langchain4j.rag.query.Metadata m) {
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            try {
                java.util.Map<String, Object> inner = m.asMap();
                if (inner != null) {
                    out.putAll(inner);
                }
            } catch (Exception ignore) {
                // asMap 사용 불가 시 chatMemoryId만 전달
            }
            Object sid = m.chatMemoryId();
            if (sid != null) {
                out.put(com.example.lms.service.rag.LangChainRAGService.META_SID, sid);
            }
            return out;
        }
        if (meta instanceof java.util.Map<?, ?> raw) {
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) raw).entrySet()) {
                Object k = e.getKey();
                if (k != null) {
                    out.put(k.toString(), e.getValue());
                }
            }
            return out;
        }
        return java.util.Map.of();
    }

    private static boolean metaBool(Map<String, Object> md, String key, boolean defaultValue) {
        if (md == null || md.isEmpty() || key == null || key.isBlank()) {
            return defaultValue;
        }
        Object v = md.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) {
            return defaultValue;
        }
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "n".equals(s)) {
            return false;
        }
        return defaultValue;
    }

    private static String metaString(Map<String, Object> md, String key) {
        if (md == null || md.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        Object v = md.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static int metaInt(Map<String, Object> md, String key, int defaultValue) {
        if (md == null || md.isEmpty() || key == null || key.isBlank()) {
            return defaultValue;
        }
        Object v = md.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return defaultValue;
            }
            return Integer.parseInt(s);
        } catch (Exception ignore) {
            return defaultValue;
        }
    }

    private static String extractUrl(String text) {
        if (text == null)
            return null;
        int a = text.indexOf("href=\"");
        if (a >= 0) {
            int s = a + 6, e = text.indexOf('"', s);
            if (e > s)
                return text.substring(s, e);
        }
        int http = text.indexOf("http");
        if (http >= 0) {
            int sp = text.indexOf(' ', http);
            return sp > http ? text.substring(http, sp) : text.substring(http);
        }
        return null;
    }

    private static boolean isOfficial(String url, List<String> officialDomains) {
        if (url == null || officialDomains == null)
            return false;
        for (String d : officialDomains) {
            if (d != null && !d.isBlank() && url.contains(d.trim()))
                return true;
        }
        return false;
    }

    /**
     * 최종 정제:
     * - dedupeKey 기준 중복 제거
     * - 공식 도메인 보너스(+0.20)
     * - 점수 내림차순 정렬 후 topK 반환
     */
    private List<Content> finalizeResults(List<Content> raw,
            String dedupeKey,
            List<String> officialDomains,
            String queryText,
            Map<String, Object> meta) {

        Map<String, Object> metaMap = (meta != null) ? meta : Map.of();

        // 1) 중복 제거 + 저관련 필터
        Map<String, Content> uniq = new LinkedHashMap<>();
        List<Content> dropped = new ArrayList<>(); // 탈락한 문서 보관용
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
            }
            if (rel < minRelatedness) {
                dropped.add(c);
                continue;
            }

            String key;
            switch (dedupeKey) {
                case "url" -> key = Optional.ofNullable(extractUrl(text)).orElse(text);
                case "hash" -> key = Integer.toHexString(text.hashCode());
                default -> key = text; // "text"
            }
            uniq.putIfAbsent(key, c);
        }
        // Safety net: 필터링 결과가 비어 있으면 원본에서 상위 topK 복구
        if (uniq.isEmpty() && !dropped.isEmpty()) {
            log.warn("[Hybrid] 모든 검색 결과가 minRelatedness({}) 미만으로 필터링됨. Safety Net 발동하여 상위 {}개 복구. (Query: {})",
                    minRelatedness, Math.min(topK, dropped.size()), queryText);
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
        }

        // 2) 경량 1차 랭킹 (없으면 candidates 그대로 사용)
        List<Content> candidates = new ArrayList<>(uniq.values());
        List<Content> firstPass = (lightWeightRanker != null)
                ? lightWeightRanker.rank(
                        candidates,
                        Optional.ofNullable(queryText).orElse(""),
                        Math.max(topK * 2, 20))
                : candidates;

        // 원소 제약 기반 보정(추천 의도·제약은 전처리기에서 유도)
        if (elementConstraintScorer != null) {
            try {
                firstPass = elementConstraintScorer.rescore(
                        Optional.ofNullable(queryText).orElse(""),
                        firstPass);
            } catch (Exception ignore) {
                /* 안전 무시 */ }
        }

        // 2-B) 🔴 (옵션) 교차엔코더 재정렬: 질문과의 의미 유사도 정밀 재계산
        // - 개선: 후보 크기뿐만 아니라 구성 가능한 재랭커 게이트에 위임하여 실행 여부를 결정합니다.
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
                shouldRerank = firstPass.size() >= rerankCeTopK;
                log.debug("[Hybrid] rerankGate error {}; falling back to size check", e.toString());
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
                    TraceStore.put("rerank.ce.skipReason", reason);
                } catch (Exception ignore) {
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
                            }
                        }
                    } catch (Exception ignore) {
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
                        }
                    } catch (Exception e) {
                        log.debug("[Hybrid] cross-encoder rerank skipped due to error: {}", e.toString());
                        try {
                            TraceStore.put("rerank.ce.skipped", true);
                            TraceStore.put("rerank.ce.skipReason", "error");
                        } catch (Exception ignore) {
                        }
                    }
                }
            } else {
                log.debug("[Hybrid] cross-encoder rerank skipped by gate");
                try {
                    TraceStore.putIfAbsent("rerank.ce.skipped", true);
                    TraceStore.putIfAbsent("rerank.ce.skipReason", "gate");
                } catch (Exception ignore) {
                }
            }
        }

        // 3) 정밀 스코어링 + 정렬
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
        // ★ NEW: 동적 랭킹 가중치/보너스
        final double wRel = hp.getDouble("retrieval.rank.w.rel", 0.60);
        final double wBase = hp.getDouble("retrieval.rank.w.base", 0.30);
        final double wAuth = hp.getDouble("retrieval.rank.w.auth", 0.10);
        final double bonusOfficial = hp.getDouble("retrieval.rank.bonus.official", 0.20);

        // ★ NEW: ML 보정 계수
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

            String url = extractUrl(text);

            double authority = authorityScorer != null ? authorityScorer.weightFor(url) : 0.5;

            double rel = 0.0;
            try {
                rel = relevanceScoringService.relatedness(
                        Optional.ofNullable(queryText).orElse(""),
                        text);
            } catch (Exception ignore) {
            }

            // ★ NEW: 최종 점수 = wRel*관련도 + wBase*기본랭크 + wAuth*Authority (+공식도메인 보너스)
            double score0 = (wRel * rel) + (wBase * base) + (wAuth * authority);
            if (isOfficial(url, officialDomains)) {
                score0 += bonusOfficial;
            }
            // ★ NEW: ML 비선형 보정(옵션) - 값域 보정 및 tail 제어
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

    // ───────────────────────────── NEW: Softmax 융합(단일 정의만 유지)
    // ─────────────────────────────
    /** 여러 버킷의 결과를 하나로 모아 점수(logit)를 만들고 softmax로 정규화한 뒤 상위 N을 고른다. */
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
                String key = Integer.toHexString(text.hashCode()); // 간단 dedupe
                String url = extractUrl(text);
                double authority = (authorityScorer != null) ? authorityScorer.weightFor(url) : 0.5;
                double related = 0.0;
                try {
                    related = relevanceScoringService.relatedness(Optional.ofNullable(queryText).orElse(""), text);
                } catch (Exception ignore) {
                }
                double base = 1.0 / (rank + 0.0); // 상위 랭크 가중
                double bucketW = 1.0 / (bIdx + 1.0); // 앞선 버킷 약간 우대
                double l = (wRelated * related) + (wAuthority * authority) + (wRank * base * bucketW);

                keeper.putIfAbsent(key, c);
                logit.merge(key, l, Math::max); // 같은 문서는 가장 높은 logit만 유지
            }
            bIdx++;
        }
        if (logit.isEmpty())
            return List.of();

        // softmax 정규화(수치 안정화 포함) 후 확률 높은 순으로 정렬
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
            log.debug("[Hybrid] softmax calibration failed: {}", e.toString());
        }
        // Compute softmax probabilities with the calibrated scores.
        double[] p = SoftmaxUtil.softmax(scores, fusionTemperature);

        // 확률 내림차순 상위 limit
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

    // [HARDENING] ensure SID metadata is present on every query
    private dev.langchain4j.rag.query.Query ensureSidMetadata(dev.langchain4j.rag.query.Query original,
            String sessionKey) {
        // Always build a new query with the correct session metadata using QueryUtils.
        // This
        // helper constructs the proper Metadata object and avoids deprecated builder
        // APIs.
        // The chat history is omitted (null) in this context.
        return QueryUtils.buildQuery(original.text(), sessionKey, null);
    }

    // Helper to compute dedupe key consistently with finalizeResults
    private String buildDedupeKey(String dedupeKey, Content c) {
        String text = java.util.Optional.ofNullable(c.textSegment())
                .map(dev.langchain4j.data.segment.TextSegment::text)
                .orElse(c.toString());
        if ("url".equalsIgnoreCase(dedupeKey)) {
            return java.util.Optional.ofNullable(extractUrl(text)).orElse(text);
        } else if ("hash".equalsIgnoreCase(dedupeKey)) {
            return Integer.toHexString(text.hashCode());
        } else {
            return text;
        }
    }

    /**
     * Convert an Object to List of Integers (for kSchedule parsing).
     */
    private java.util.List<Integer> toIntList(Object o) {
        if (o instanceof java.util.List<?> list) {
            return list.stream()
                    .map(v -> {
                        if (v instanceof Number n)
                            return n.intValue();
                        try {
                            return Integer.parseInt(String.valueOf(v));
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList());
        }
        return null;
    }

}

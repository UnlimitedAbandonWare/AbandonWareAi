package com.example.lms.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import com.example.lms.llm.NoopEmbeddingModel;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.infra.resilience.SidRotationAdvisor;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.scope.ScopeHeuristics;
import com.example.lms.service.vector.VectorSidService;
import com.example.lms.service.EmbeddingStoreManager;
import com.example.lms.service.guard.VectorPoisonGuard;
import com.example.lms.service.guard.VectorQualityGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class LangChainRAGService {

    private static final Logger log = LoggerFactory.getLogger(LangChainRAGService.class);

    // 다른 서비스(ChatService, HybridRetriever 등)에서 참조하는 상수 정의
    public static final String META_SID = "sid";
    // MERGE_HOOK:PROJ_AGENT::GLOBAL_SID_PUBLIC_V1
    public static final String GLOBAL_SID = "__PRIVATE__";
    public static final double DEFAULT_VECTOR_MIN_SCORE = 0.6;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    // lazy bootstrap: 검색 결과가 비었을 때만 1회 세션 메모리 적재
    @Autowired(required = false)
    private EmbeddingStoreManager embeddingStoreManager;

    @Autowired(required = false)
    private VectorPoisonGuard vectorPoisonGuard;


    @Autowired(required = false)
    private VectorQualityGuard vectorQualityGuard;
    @Autowired(required = false)
    private VectorSidService vectorSidService;

    @Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @Autowired(required = false)
    private SidRotationAdvisor sidRotationAdvisor;

    @Value("${vector.bootstrap.on-empty.enabled:true}")
    private boolean bootstrapOnEmptyEnabled;

    @Value("${vector.bootstrap.on-empty.limit:200}")
    private int bootstrapOnEmptyLimit;

    // [PATCH] Retrieval doc_type 필터 (fail-soft)
    @Value("${vector.retrieval.docTypeFilter.enabled:true}")
    private boolean docTypeFilterEnabled;

    @Value("${vector.retrieval.docTypeFilter.allowed:KB,MEMORY,LEGACY}")
    private String docTypeAllowedCsv;

    @Value("${vector.retrieval.docTypeFilter.minMatches:2}")
    private int docTypeFilterMinMatches;

    @Value("${vector.retrieval.scopeFilter.enabled:false}")
    private boolean scopeFilterEnabled;

    @Value("${vector.retrieval.scopeFilter.minMatches:1}")
    private int scopeFilterMinMatches;

    public LangChainRAGService(EmbeddingModel em, EmbeddingStore<TextSegment> es) {
        this.embeddingModel = em;
        this.embeddingStore = es;
    }

    @PostConstruct
    public void logVectorState() {
        try {
            log.info("[RAG] EmbeddingStore initialized: {}", embeddingStore.getClass().getSimpleName());
            log.info("[RAG] EmbeddingModel type: {}", embeddingModel.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("[RAG] logVectorState failed: {}", e.getMessage());
        }
    }

    private String activeGlobalSid() {
        try {
            if (vectorSidService == null) return GLOBAL_SID;
            String s = vectorSidService.resolveActiveSid(GLOBAL_SID);
            return (s == null || s.isBlank()) ? GLOBAL_SID : s.trim();
        } catch (Exception ignore) {
            return GLOBAL_SID;
        }
    }


    /**
     * HybridRetriever 등 다른 컴포넌트와의 호환성을 위해 ContentRetriever 변환 제공.
     * 1.0.1 버전의 표준 구현체인 EmbeddingStoreContentRetriever를 사용합니다.
     */
    public ContentRetriever asContentRetriever(String indexName) {
        // indexName은 사용하는 VectorStore 구현체에 따라 다를 수 있으나,
        // 여기서는 기본 Store를 래핑하여 반환합니다.
        //
        // [HARDENING]
        // - vecTopK(또는 vectorTopK) 힌트가 Query.metadata()로 내려오는 경우, 고정 5개가 아니라
        // 요청별로 maxResults를 동적으로 적용한다.
        // - sid(Session) 가 있으면 (세션 SHORT_TERM) OR (GLOBAL_KNOWLEDGE)로 제한 검색하여
        // 불필요한 오염/스팸 컨텍스트를 줄인다.
        return new ContentRetriever() {
            @Override
            public List<Content> retrieve(Query q) {
                if (q == null || q.text() == null || q.text().isBlank()) {
                    return List.of();
                }
                if (embeddingModel instanceof NoopEmbeddingModel) {
                    return List.of();
                }

                Map<String, Object> meta = toMetaMap(q);
                int k = resolveTopK(meta, 5);
                double minScore = resolveMinScore(meta, 0.6);
                String sid = Objects.toString(meta.get(META_SID), "").trim();

                try {
                    if (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.RETRIEVAL_VECTOR_POISON)) {
                        TraceStore.put("vector.poisoning.bypass", true);
                        return List.of();
                    }

                    String qText = q.text();
                    if (vectorPoisonGuard != null) {
                        qText = vectorPoisonGuard.sanitizeQueryForVectorSearch(qText);
                    }

                    Embedding emb = embeddingModel.embed(qText).content();
                    if (emb == null || emb.vector() == null || emb.vector().length == 0) {
                        return List.of();
                    }

                    int poolK = (vectorPoisonGuard != null) ? Math.min(30, Math.max(k, k * 4)) : k;

                    // [PATCH] doc_type + (optional) scope 필터를 적용하되,
                    // 너무 선택적이면(few/zero matches) 단계적으로 완화(fail-soft)
                    Filter scopeFilter = (scopeFilterEnabled ? buildScopeFilterFromMeta(meta) : null);
                    boolean usedScope = (scopeFilter != null);

                    Filter filter = buildSidOnlyFilterForSid(sid);
                    if (docTypeFilterEnabled) {
                        filter = andSafe(filter, buildDocTypeAllowedFilter());
                        filter = andSafe(filter, buildDocTypeAllowedFilterFromMeta(meta));
                    }
                    if (usedScope) {
                        filter = andSafe(filter, scopeFilter);
                    }

                    EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                            .queryEmbedding(emb)
                            .maxResults(poolK)
                            .minScore(minScore)
                            .filter(filter)
                            .build();

                    EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
                    List<EmbeddingMatch<TextSegment>> matches = (result == null || result.matches() == null)
                            ? Collections.emptyList()
                            : result.matches();

                    // 1) Scope relax (more aggressive narrowing than doc_type)
                    if (usedScope && matches.size() < Math.max(1, scopeFilterMinMatches)) {
                        try {
                            TraceStore.put("vector.scopeFilter.relaxed", true);
                        } catch (Exception ignore) {
                        }

                        Filter relaxedScope = buildSidOnlyFilterForSid(sid);
                        if (docTypeFilterEnabled) {
                            relaxedScope = andSafe(relaxedScope, buildDocTypeAllowedFilter());
                            relaxedScope = andSafe(relaxedScope, buildDocTypeAllowedFilterFromMeta(meta));
                        }

                        EmbeddingSearchRequest relaxedScopeReq = EmbeddingSearchRequest.builder()
                                .queryEmbedding(emb)
                                .maxResults(poolK)
                                .minScore(minScore)
                                .filter(relaxedScope)
                                .build();

                        EmbeddingSearchResult<TextSegment> relaxedScopeRes = embeddingStore.search(relaxedScopeReq);
                        if (relaxedScopeRes != null && relaxedScopeRes.matches() != null) {
                            matches = relaxedScopeRes.matches();
                        }
                    }

                    // 2) doc_type relax
                    if (docTypeFilterEnabled && matches.size() < Math.max(1, docTypeFilterMinMatches)) {
                        try {
                            TraceStore.put("vector.docTypeFilter.relaxed", true);
                        } catch (Exception ignore) {
                        }

                        Filter relaxed = buildSidOnlyFilterForSid(sid);
                        EmbeddingSearchRequest relaxedRequest = EmbeddingSearchRequest.builder()
                                .queryEmbedding(emb)
                                .maxResults(poolK)
                                .minScore(minScore)
                                .filter(relaxed)
                                .build();

                        EmbeddingSearchResult<TextSegment> relaxedResult = embeddingStore.search(relaxedRequest);
                        if (relaxedResult != null && relaxedResult.matches() != null) {
                            matches = relaxedResult.matches();
                        }
                    }

                    if (matches == null || matches.isEmpty()) {
                        return List.of();
                    }
                    if (vectorPoisonGuard != null) {
                        int raw = matches.size();
                        matches = vectorPoisonGuard.filterMatches(matches, sid);
                        int kept = (matches == null ? 0 : matches.size());
                        int dropped = Math.max(0, raw - kept);
                        if (nightmareBreaker != null && raw >= 3) {
                            double ratio = raw == 0 ? 0.0 : ((double) dropped / (double) raw);
                            if (dropped >= 2 && ratio >= 0.60) {
                                nightmareBreaker.recordRejected(NightmareKeys.RETRIEVAL_VECTOR_POISON, qText,
                                        "vector.poison.filtered");
                                // [AUTO-RECOMMEND] repeated poison drops -> recommend sid rotation (fail-soft)
                                try {
                                    if (sidRotationAdvisor != null) {
                                        sidRotationAdvisor.recordPoison(sid,
                                                "retrieval_vector_poison ratio="
                                                        + String.format(java.util.Locale.ROOT, "%.2f", ratio));
                                    }
                                } catch (Exception ignore) {
                                    // fail-soft
                                }
                            }
                        }
                    }

                    if (vectorQualityGuard != null) {
                        matches = vectorQualityGuard.filterMatches(matches, qText, "rag.vector");
                    }

                    if (matches == null || matches.isEmpty()) {
                        return List.of();
                    }
                    List<Content> out = new ArrayList<>();
                    for (EmbeddingMatch<TextSegment> match : matches) {
                        if (match == null || match.embedded() == null)
                            continue;
                        // Preserve TextSegment metadata (url/source/title 등) for downstream citations.
                        out.add(Content.from(match.embedded()));
                        if (out.size() >= k) {
                            break;
                        }
                    }
                    return out;
                } catch (Exception e) {
                    log.debug("[RAG] retrieve failed (fail-soft): {}", e.toString());
                    return List.of();
                }
            }
        };
    }

    /**
     * Retrieve verified knowledge snippets (DomainKnowledge) stored in the global
     * KB vector space,
     * filtered by {@code kb_domain}.
     *
     * <p>
     * This is used to give certain KB domains (e.g. UAW thumbnails) a "priority
     * recall" pass before
     * the broader semantic retrieval. Filtering by kb_domain is done client-side to
     * stay compatible
     * with embedding stores that may not support arbitrary metadata AND filters.
     */
    public List<Content> retrieveGlobalKbDomain(Query query, String kbDomain, int topK, double minScore,
            int poolK) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        if (kbDomain == null || kbDomain.isBlank()) {
            return List.of();
        }
        if (topK <= 0) {
            return List.of();
        }
        if (embeddingModel == null || embeddingModel instanceof NoopEmbeddingModel) {
            return List.of();
        }

        int maxResults = Math.max(topK, Math.max(1, poolK));
        // Keep the pool bounded to avoid big remote fetches in worst-case.
        maxResults = Math.min(maxResults, 64);

        double requestMinScore = (minScore > 0 && minScore <= 1.0) ? minScore : DEFAULT_VECTOR_MIN_SCORE;

        try {
            Embedding queryEmbedding = embeddingModel.embed(query.text()).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(requestMinScore)
                    // Global KB space only
                    .filter(metadataKey(META_SID).isEqualTo(activeGlobalSid()))
                    .build();

            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
            if (result == null || result.matches() == null || result.matches().isEmpty()) {
                return List.of();
            }

            List<Content> out = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : result.matches()) {
                if (match == null || match.embedded() == null) {
                    continue;
                }
                TextSegment seg = match.embedded();
                String dom = null;
                try {
                    dom = seg.metadata() != null ? seg.metadata().getString("kb_domain") : null;
                } catch (Exception ignore) {
                    dom = null;
                }
                if (dom == null || !kbDomain.equalsIgnoreCase(dom)) {
                    continue;
                }

                // Preserve TextSegment metadata (kb_domain/kb_entity/source/url/title, ...)
                out.add(Content.from(seg));
                if (out.size() >= topK) {
                    break;
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("[RAG] retrieveGlobalKbDomain failed (fail-soft): {}", e.toString());
            return List.of();
        }
    }

    // ---- Query metadata helpers (version-safe) ----
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMetaMap(Query q) {
        if (q == null || q.metadata() == null) {
            return java.util.Collections.emptyMap();
        }
        Object meta = q.metadata();
        if (meta instanceof Map<?, ?> raw) {
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() != null)
                    out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        try {
            java.lang.reflect.Method m = meta.getClass().getMethod("asMap");
            Object v = m.invoke(meta);
            if (v instanceof Map<?, ?> m2) {
                java.util.Map<String, Object> out = new java.util.HashMap<>();
                for (Map.Entry<?, ?> e : m2.entrySet()) {
                    if (e.getKey() != null)
                        out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            }
        } catch (Exception ignore) {
            // fall through
        }
        return java.util.Collections.emptyMap();
    }

    private static int resolveTopK(Map<String, Object> meta, int def) {
        int k = metaInt(meta, "vectorTopK", -1);
        if (k <= 0) {
            k = metaInt(meta, "vecTopK", -1);
        }
        if (k <= 0) {
            k = metaInt(meta, "vec_top_k", -1);
        }
        if (k <= 0) {
            k = def;
        }
        return Math.max(1, Math.min(k, 50));
    }

    private static double resolveMinScore(Map<String, Object> meta, double def) {
        Object v = meta != null ? meta.get("vecMinScore") : null;
        if (v == null)
            v = meta != null ? meta.get("vectorMinScore") : null;
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return (d > 0 && d <= 1.0) ? d : def;
        }
        if (v instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                return (d > 0 && d <= 1.0) ? d : def;
            } catch (Exception ignore) {
                return def;
            }
        }
        return def;
    }

    private static int metaInt(Map<String, Object> meta, String key, int def) {
        if (meta == null || key == null)
            return def;
        Object v = meta.get(key);
        if (v instanceof Number n)
            return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception ignore) {
                return def;
            }
        }
        return def;
    }

    private Filter buildFilterForSid(String sid, boolean includeDocType) {
        Filter sidFilter = buildSidOnlyFilterForSid(sid);
        if (!includeDocType || !docTypeFilterEnabled) {
            return sidFilter;
        }
        Filter dt = buildDocTypeAllowedFilter();
        if (dt == null) {
            return sidFilter;
        }
        try {
            // sid AND doc_type
            return sidFilter.and(dt);
        } catch (Throwable t) {
            // AND 연산 미지원/예외 시 sid-only로 폴백 (client-side guard가 보완)
            return sidFilter;
        }
    }

    private Filter buildSidOnlyFilterForSid(String sid) {
        try {
            String s = (sid == null) ? "" : sid.trim();

            // __TRANSIENT__ is treated as "no session" (global only)
            if ("__TRANSIENT__".equalsIgnoreCase(s)) {
                s = "";
            }

            // 글로벌 풀(EmbeddingStoreManager 기본값)
            Filter global = metadataKey(META_SID).isEqualTo(activeGlobalSid());

            if (!s.isBlank()) {
                // Compatibility: some components store numeric sids, others store "chat-<n>".
                Filter session = metadataKey(META_SID).isEqualTo(s);
                if (s.matches("\\d+")) {
                    session = session.or(metadataKey(META_SID).isEqualTo("chat-" + s));
                } else if (s.startsWith("chat-") && s.length() > 5 && s.substring(5).matches("\\d+")) {
                    session = session.or(metadataKey(META_SID).isEqualTo(s.substring(5)));
                }
                return session.or(global);
            }
            return global;
        } catch (Exception ex) {
            // fail-closed: on filter build errors, only allow global pool.
            log.warn("[RAG] Cannot build metadata filter (fail-closed to global). sid={}, err={}", sid, ex.getMessage());
            return metadataKey(META_SID).isEqualTo(activeGlobalSid());
        }
    }

    private Filter buildDocTypeAllowedFilter() {
        if (docTypeAllowedCsv == null || docTypeAllowedCsv.isBlank()) return null;
        Filter out = null;
        for (String t : docTypeAllowedCsv.split(",")) {
            String type = (t == null) ? "" : t.trim().toUpperCase(java.util.Locale.ROOT);
            if (type.isBlank()) continue;
            Filter one = metadataKey(VectorMetaKeys.META_DOC_TYPE).isEqualTo(type);
            out = (out == null) ? one : out.or(one);
        }
        return out;
    }


    /**
     * Build a doc_type allowlist filter from query metadata (allowed_doc_types).
     *
     * <p>Fail-soft and safe by default:
     * <ul>
     *   <li>Filters out internal/noisy types (LOG/TRACE/QUARANTINE)</li>
     *   <li>Intersects with server-side allowed list (vector.retrieval.docTypeFilter.allowed) when present</li>
     * </ul>
     * </p>
     */
    private Filter buildDocTypeAllowedFilterFromMeta(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return null;
        Object v = meta.get(VectorMetaKeys.META_ALLOWED_DOC_TYPES);
        if (v == null) return null;

        String csv = String.valueOf(v).trim();
        if (csv.isBlank()) return null;

        java.util.Set<String> requested = new java.util.LinkedHashSet<>();
        for (String t : csv.split(",")) {
            String s = (t == null) ? "" : t.trim().toUpperCase(java.util.Locale.ROOT);
            if (!s.isBlank()) requested.add(s);
        }
        // Always exclude internal/noisy types
        requested.remove("LOG");
        requested.remove("TRACE");
        requested.remove("QUARANTINE");

        if (requested.isEmpty()) return null;

        // Intersect with server-side allowlist when configured
        java.util.Set<String> safe = new java.util.LinkedHashSet<>();
        if (docTypeAllowedCsv != null && !docTypeAllowedCsv.isBlank()) {
            for (String t : docTypeAllowedCsv.split(",")) {
                String s = (t == null) ? "" : t.trim().toUpperCase(java.util.Locale.ROOT);
                if (!s.isBlank()) safe.add(s);
            }
            safe.remove("LOG");
            safe.remove("TRACE");
            safe.remove("QUARANTINE");
        }
        if (!safe.isEmpty()) {
            requested.retainAll(safe);
        }
        if (requested.isEmpty()) return null;

        Filter out = null;
        for (String dt : requested) {
            Filter one = metadataKey(VectorMetaKeys.META_DOC_TYPE).isEqualTo(dt);
            out = (out == null) ? one : out.or(one);
        }
        return out;
    }

    /**
     * Build a scope filter from query metadata (scope_anchor_key / scope_part_key).
     * Only applied when {@code vector.retrieval.scopeFilter.enabled=true}.
     */
    private Filter buildScopeFilterFromMeta(Map<String, Object> meta) {
        if (!scopeFilterEnabled) return null;
        if (meta == null || meta.isEmpty()) return null;

        String anchor = String.valueOf(meta.getOrDefault(VectorMetaKeys.META_SCOPE_ANCHOR_KEY, "")).trim();
        if (anchor.isBlank()) return null;

        // Normalize to match ingest-side normalization
        anchor = ScopeHeuristics.normalizeKey(anchor);
        if (anchor.isBlank()) return null;

        Filter f = metadataKey(VectorMetaKeys.META_SCOPE_ANCHOR_KEY).isEqualTo(anchor);

        String kind = String.valueOf(meta.getOrDefault(VectorMetaKeys.META_SCOPE_KIND, "")).trim();
        String part = String.valueOf(meta.getOrDefault(VectorMetaKeys.META_SCOPE_PART_KEY, "")).trim();
        if ("PART".equalsIgnoreCase(kind) && !part.isBlank()) {
            String pk = ScopeHeuristics.normalizeKey(part);
            if (!pk.isBlank()) {
                f = andSafe(f, metadataKey(VectorMetaKeys.META_SCOPE_PART_KEY).isEqualTo(pk));
            }
        }
        return f;
    }

    private Filter andSafe(Filter left, Filter right) {
        if (left == null) return right;
        if (right == null) return left;
        try {
            return left.and(right);
        } catch (Throwable t) {
            return left;
        }
    }


    public List<String> retrieveRagContext(String query, String sid) {
        try {
            // 벡터 기능 OFF 상태 빠른 탈출
            if (embeddingModel instanceof NoopEmbeddingModel) {
                log.info("[RAG] EmbeddingModel is NoopEmbeddingModel; skipping vector search");
                return java.util.Collections.emptyList();
            }

            if (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.RETRIEVAL_VECTOR_POISON)) {
                TraceStore.put("vector.poisoning.bypass", true);
                return java.util.Collections.emptyList();
            }

            String qText = (query == null) ? "" : query;
            if (vectorPoisonGuard != null) {
                qText = vectorPoisonGuard.sanitizeQueryForVectorSearch(qText);
            }

            // 1. 질문 임베딩
            Embedding emb = embeddingModel.embed(qText).content();
            if (emb == null || emb.vector() == null || emb.vector().length == 0) {
                log.warn("[RAG] empty embedding returned; skipping vector search");
                return java.util.Collections.emptyList();
            }

            // 2. 검색 요청 객체 생성 (LangChain4j 1.0.x 스타일)
            // - 세션이 있으면: (sid == 요청 sid) OR (sid == __PRIVATE__)
            // - 세션이 없으면: (sid == __PRIVATE__)
            int poolK = Math.min(30, Math.max(5, 5 * 4));

            // [PATCH] doc_type 필터를 적용하되, 너무 선택적이면(few/zero matches) sid-only로 완화(fail-soft)
            Filter filter = buildFilterForSid(sid, true);

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(emb)
                    .maxResults(poolK)
                    .minScore(0.6) // 유사도 임계값
                    .filter(filter)
                    .build();

            // 3. 검색 수행
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = (result == null || result.matches() == null)
                    ? Collections.emptyList()
                    : result.matches();

            if (docTypeFilterEnabled && matches.size() < Math.max(1, docTypeFilterMinMatches)) {
                try {
                    TraceStore.put("vector.docTypeFilter.relaxed", true);
                } catch (Exception ignore) {
                }
                Filter relaxed = buildFilterForSid(sid, false);
                EmbeddingSearchRequest relaxedRequest = EmbeddingSearchRequest.builder()
                        .queryEmbedding(emb)
                        .maxResults(poolK)
                        .minScore(0.6)
                        .filter(relaxed)
                        .build();
                EmbeddingSearchResult<TextSegment> relaxedResult = embeddingStore.search(relaxedRequest);
                if (relaxedResult != null && relaxedResult.matches() != null) {
                    matches = relaxedResult.matches();
                }
            }

            if (matches == null || matches.isEmpty()) {
                log.debug("[RAG] Vector 0 matches sid={}", sid);
                return java.util.Collections.emptyList();
            }
            int raw = (matches == null ? 0 : matches.size());
            if (vectorPoisonGuard != null && matches != null) {
                matches = vectorPoisonGuard.filterMatches(matches, sid);
            }
            if (vectorQualityGuard != null && matches != null) {
                matches = vectorQualityGuard.filterMatches(matches, qText, "rag.vector");
            }
            int kept = (matches == null ? 0 : matches.size());

            if (nightmareBreaker != null && raw >= 3) {
                int dropped = raw - kept;
                double ratio = raw <= 0 ? 0.0 : ((double) dropped / (double) raw);
                if (dropped >= 2 && ratio >= 0.6) {
                    nightmareBreaker.recordRejected(NightmareKeys.RETRIEVAL_VECTOR_POISON, qText,
                            "vector.poison.filtered");
                    // [AUTO-RECOMMEND] repeated poison drops -> recommend sid rotation (fail-soft)
                    try {
                        if (sidRotationAdvisor != null) {
                            sidRotationAdvisor.recordPoison(sid,
                                    "retrieval_vector_poison ratio="
                                            + String.format(java.util.Locale.ROOT, "%.2f", ratio));
                        }
                    } catch (Exception ignore) {
                        // fail-soft
                    }
                }
            }

            if (matches == null || matches.isEmpty()) {
                log.debug("[RAG] Vector 0 matches sid={}", sid);
                return java.util.Collections.emptyList();
            }

            List<String> out = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                if (match == null || match.embedded() == null)
                    continue;
                out.add(match.embedded().text());
                if (out.size() >= 5) {
                    break;
                }
            }
            return out;

        } catch (Exception e) {
            log.warn("[RAG] retrieve error {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}

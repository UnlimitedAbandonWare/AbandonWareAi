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
import com.example.lms.service.EmbeddingStoreManager;
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

    @Value("${vector.bootstrap.on-empty.enabled:true}")
    private boolean bootstrapOnEmptyEnabled;

    @Value("${vector.bootstrap.on-empty.limit:200}")
    private int bootstrapOnEmptyLimit;

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
                    Embedding emb = embeddingModel.embed(q.text()).content();
                    if (emb == null || emb.vector() == null || emb.vector().length == 0) {
                        return List.of();
                    }

                    Filter filter = buildFilterForSid(sid);
                    EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                            .queryEmbedding(emb)
                            .maxResults(k)
                            .minScore(minScore)
                            .filter(filter)
                            .build();

                    EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
                    if ((result == null || result.matches() == null || result.matches().isEmpty()) && filter != null) {
                        // legacy/metadata-missing fallback (fail-soft): re-run once without filter
                        EmbeddingSearchRequest fallbackReq = EmbeddingSearchRequest.builder()
                                .queryEmbedding(emb)
                                .maxResults(k)
                                .minScore(minScore)
                                .build();
                        result = embeddingStore.search(fallbackReq);
                    }

                    if (result == null || result.matches() == null || result.matches().isEmpty()) {
                        return List.of();
                    }
                    List<Content> out = new ArrayList<>();
                    for (EmbeddingMatch<TextSegment> match : result.matches()) {
                        if (match == null || match.embedded() == null)
                            continue;
                        // Preserve TextSegment metadata (url/source/title 등) for downstream citations.
                        out.add(Content.from(match.embedded()));
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
                    .filter(metadataKey(META_SID).isEqualTo(GLOBAL_SID))
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

    private static Filter buildFilterForSid(String sid) {
        try {
            String s = (sid == null) ? "" : sid.trim();

            // __TRANSIENT__ is treated as "no session" (global only)
            if ("__TRANSIENT__".equalsIgnoreCase(s)) {
                s = "";
            }

            // 글로벌 풀(EmbeddingStoreManager 기본값)
            Filter global = metadataKey(META_SID).isEqualTo(GLOBAL_SID);

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
        } catch (Exception ignore) {
            // 필터 기능이 스토어에 없거나 예외면 fail-open
            return null;
        }
    }

    public List<String> retrieveRagContext(String query, String sid) {
        try {
            // 벡터 기능 OFF 상태 빠른 탈출
            if (embeddingModel instanceof NoopEmbeddingModel) {
                log.info("[RAG] EmbeddingModel is NoopEmbeddingModel; skipping vector search");
                return java.util.Collections.emptyList();
            }

            // 1. 질문 임베딩
            Embedding emb = embeddingModel.embed(query).content();
            if (emb == null || emb.vector() == null || emb.vector().length == 0) {
                log.warn("[RAG] empty embedding returned; skipping vector search");
                return java.util.Collections.emptyList();
            }

            // 2. 검색 요청 객체 생성 (LangChain4j 1.0.x 스타일)
            // - 세션이 있으면: (sid == 요청 sid) OR (sid == __PRIVATE__)
            // - 세션이 없으면: (sid == __PRIVATE__)
            Filter filter = buildFilterForSid(sid);

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(emb)
                    .maxResults(5)
                    .minScore(0.6) // 유사도 임계값
                    .filter(filter)
                    .build();

            // 3. 검색 수행
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

            if ((result == null || result.matches() == null || result.matches().isEmpty()) && filter != null) {
                // legacy/metadata-missing fallback (fail-soft): re-run once without filter
                EmbeddingSearchRequest fallbackReq = EmbeddingSearchRequest.builder()
                        .queryEmbedding(emb)
                        .maxResults(5)
                        .minScore(0.6)
                        .build();
                result = embeddingStore.search(fallbackReq);
            }

            if (result == null || result.matches() == null || result.matches().isEmpty()) {
                log.debug("[RAG] Vector 0 matches sid={}", sid);
                return java.util.Collections.emptyList();
            }

            List<String> out = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : result.matches()) {
                if (match == null || match.embedded() == null)
                    continue;
                out.add(match.embedded().text());
            }
            return out;

        } catch (Exception e) {
            log.warn("[RAG] retrieve error {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}

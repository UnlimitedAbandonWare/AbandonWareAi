package com.example.lms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import com.example.lms.memory.PersistentChatMemory;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.fusion.ReciprocalRankFuser;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import com.example.lms.service.embedding.DecoratingEmbeddingModel;
import com.example.lms.service.embedding.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.lms.service.rag.extract.PageContentScraper;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.config.VectorStoreHealthIndicator;
import com.example.lms.service.vector.UpstashVectorStoreAdapter;
import com.example.lms.service.soak.metrics.SoakMetricRegistry;
import org.springframework.web.reactive.function.client.WebClient;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import java.util.List;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import com.acme.aicore.adapters.search.CachedWebSearch;
import com.acme.aicore.domain.ports.WebSearchProvider;

// Use SLF4J Logger directly instead of Lombok 

import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore; // fallback용

// Added for Upstash vector store integration
@Configuration
@EnableConfigurationProperties(PineconeProps.class)
public class LangChainConfig {
    private static final Logger log = LoggerFactory.getLogger(LangChainConfig.class);

    /**
     * Static logger for this configuration. Using an explicit Logger avoids the
     * need
     * for Lombok and ensures the application can compile in environments without
     * annotation processing.
     */
    private final ChatMessageRepository msgRepo;
    private final ChatSessionRepository sesRepo;

    public LangChainConfig(ChatMessageRepository msgRepo, ChatSessionRepository sesRepo) {
        this.msgRepo = msgRepo;
        this.sesRepo = sesRepo;
    }

    /* ───── OpenAI / Pinecone 공통 ───── */
    // Resolve the OpenAI/Groq API key from configuration or environment. Use
    // the `openai.api.key` property when provided and fall back to the
    // OPENAI_API_KEY or GROQ_API_KEY environment variables otherwise.
    // API key must be supplied via properties; environment fallbacks are
    // intentionally
    // disallowed to enforce explicit configuration and avoid leaking secrets via
    // env vars.
    @Value("${openai.api.key}")
    private String openAiKey;
    @Value("${openai.chat.model:gemma3:27b}")
    private String chatModelName;
    @Value("${openai.chat.temperature:0.7}")
    private double chatTemperature;
    @Value("${openai.timeout-seconds:60}")
    private long openAiTimeoutSec;

    @Value("${pinecone.api.key}")
    private String pcKey;
    @Value("${pinecone.environment}")
    private String pcEnv;
    @Value("${pinecone.project.id}")
    private String pcProjectId;
    @Value("${pinecone.index.name}")
    private String pcIndex;

    @Value("${embedding.model:${pinecone.embedding-model:text-embedding-3-small}}")
    private String embeddingModelName;

    @Value("${embedding.provider:ollama}")
    private String embeddingProvider;

    @Value("${embedding.base-url:http://localhost:11435/api/embed}")
    private String embeddingBaseUrl;

    @Value("${embedding.dimensions:1536}")
    private int embeddingDimensions;

    @Value("${embedding.timeout-seconds:30}")
    private long embeddingTimeoutSec;

    /**
     * Determines whether the application should fail fast if the vector store
     * (e.g. Pinecone) cannot be initialized. When set to {@code true}, any
     * exception thrown during vector store initialization will be rethrown,
     * causing the application context to fail to start. When {@code false},
     * the application will log an error, register a DOWN health status and
     * fall back to an in-memory embedding store instead.
     */
    @Value("${vector.store.failfast:false}")
    private boolean vectorStoreFailfast;

    /* ───── Self-Ask 검색 튜닝 ───── */
    @Value("${search.selfask.max-depth:2}")
    private int selfAskMaxDepth;
    @Value("${search.selfask.web-top-k:8}")
    private int selfAskWebTopK;
    @Value("${search.selfask.overall-top-k:10}")
    private int selfAskOverallTopK;

    @Bean("persistentChatMemoryProvider")
    public ChatMemoryProvider persistentChatMemoryProvider() {
        return id -> new PersistentChatMemory(id.toString(), msgRepo, sesRepo);
    }

    /* ═════════ 1. LLM / 임베딩 ═════════ */
    // [REMOVED] chatModel 빈은 LlmConfig.java에서 @Primary로 정의됨
    // BeanDefinitionOverrideException 방지를 위해 중복 정의 제거
    /*
     * @Bean
     * 
     * @ConditionalOnMissingBean(ChatModel.class)
     * public ChatModel chatModel(@Value("${lms.use-rag:true}") boolean
     * useRagDefault) {
     * if (openAiKey == null || openAiKey.isBlank()) {
     * throw new
     * IllegalStateException("openai.api.key is missing (ENV fallback disabled by policy)"
     * );
     * }
     * return OpenAiChatModel.builder()
     * .apiKey(openAiKey)
     * .modelName(chatModelName)
     * .temperature(useRagDefault ? chatTemperature : 0.0)
     * .timeout(Duration.ofSeconds(openAiTimeoutSec))
     * .build();
     * }
     */

    /**
     * 추천/조합(RECOMMENDATION) 전용 상위 모델(저온)
     */
    @Bean("moeChatModel")
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel moeChatModel(
            // Default to gpt-4 for the MOE recommender model
            @Value("${openai.chat.model.moe:gpt-4}") String moeModel,
            @Value("${openai.chat.temperature.recommender:0.2}") double recTemp) {
        return OpenAiChatModel.builder()
                .apiKey(openAiKey)
                .modelName(moeModel)
                .temperature(recTemp)
                .timeout(Duration.ofSeconds(openAiTimeoutSec))
                .build();
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(OllamaEmbeddingModel ollamaEmbeddingModel) {

        String provider = (embeddingProvider == null ? "" : embeddingProvider.trim().toLowerCase());

        EmbeddingModel delegate;

        switch (provider) {
            case "openai":
                if (openAiKey == null || openAiKey.isBlank()) {
                    throw new IllegalStateException(
                            "embedding.provider=openai 인데 openai.api.key 가 설정되어 있지 않습니다.");
                }
                delegate = OpenAiEmbeddingModel.builder()
                        .apiKey(openAiKey)
                        .modelName(embeddingModelName)
                        .timeout(Duration.ofSeconds(openAiTimeoutSec))
                        .build();
                break;

            case "none":
                // 완전 web-only RAG 모드 (임베딩 비활성화)
                delegate = new com.example.lms.llm.NoopEmbeddingModel(embeddingDimensions);
                break;

            case "ollama":
            default:
                // 기본값: 로컬 Ollama /api/embed
                delegate = ollamaEmbeddingModel;
                break;
        }

        // 공통: 임베딩 캐시 데코레이터로 감싸서 중복 embed 호출 줄이기
        return new DecoratingEmbeddingModel(
                delegate,
                null, // null이면 EmbeddingCache.InMemory 사용
                java.time.Duration.ofMinutes(15));
    }

    // LangChainConfig.java
    @Bean
    @ConditionalOnMissingBean(QueryTransformer.class)
    public QueryTransformer queryTransformer(ChatModel llm) {
        return new QueryTransformer(llm);
    }

    @Bean
    @SuppressWarnings({ "removal" })
    @ConditionalOnProperty(name = "vector.store", havingValue = "pinecone", matchIfMissing = true) // 기본 pinecone
    @Lazy // 초기 연결을 최대한 늦춤
    public EmbeddingStore<TextSegment> pineconeEmbeddingStore(
            PineconeProps p,
            com.example.lms.vector.EmbeddingFingerprint embeddingFingerprint) {
        EmbeddingStore<TextSegment> base;
        try {
            base = PineconeEmbeddingStore.builder()
                    .apiKey(p.getApiKey())
                    .environment(p.getEnvironment())
                    .projectId(p.getProjectId())
                    .index(p.getIndex())
                    .nameSpace(p.getNamespace())
                    .build();
        } catch (Throwable t) {
            if (vectorStoreFailfast) {
                // fail-fast: propagate the exception to prevent silent fallback in production
                throw t;
            }
            // fail-soft: log the error and fall back to an in-memory embedding store
            log.error("Pinecone init failed; falling back to InMemoryEmbeddingStore", t);
            base = new InMemoryEmbeddingStore<>();
        }

        // Prevent cross-embedding-model contamination by stamping and filtering using the current embedding fingerprint.
        return new com.example.lms.vector.FingerprintAwareEmbeddingStore(base, embeddingFingerprint);
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingStore.class)
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    /*
     * ═════════ 1b. Upstash Vector Store (READ-ONLY) ═════════
     *
     * The Upstash vector store adapter wraps the Upstash REST API and
     * implements the {@link EmbeddingStore} interface. It is used as
     * the read path for vector queries while writing operations are
     * delegated to the Pinecone store. When Upstash is not configured
     * (blank rest URL or API key) the adapter gracefully returns empty
     * results.
     */
    // UpstashVectorStoreAdapter bean. When not configured (blank URL/API key),
// the adapter degrades to empty results safely.
@Bean
@ConditionalOnMissingBean
public UpstashVectorStoreAdapter upstashVectorStoreAdapter(@Qualifier("defaultWebClient") WebClient webClient) {
    return new UpstashVectorStoreAdapter(webClient);
}
    /**
     * Composite embedding store that routes read and write operations to
     * different backends. Reads (search) are served from Upstash
     * Vector DB, while writes (add/insert) are delegated to the
     * Pinecone store. When either backend fails the operation
     * degrades to the other without propagating the exception.
     */
    @Bean
    @ConditionalOnProperty(name = "retrieval.vector.enabled", havingValue = "true", matchIfMissing = true)
    public EmbeddingStore<TextSegment> embeddingStore(
            UpstashVectorStoreAdapter upstash,
            @Qualifier("pineconeEmbeddingStore") ObjectProvider<EmbeddingStore<TextSegment>> pineconeProvider,
            com.example.lms.vector.EmbeddingFingerprint embeddingFingerprint,
            SoakMetricRegistry metricRegistry,
            @Value("${vector.store:pinecone}") String vectorStoreChoice) {
        return new EmbeddingStore<>() {
private final EmbeddingStore<TextSegment> pineconeOrMemory =
                    pineconeProvider.getIfAvailable(() -> new InMemoryEmbeddingStore<>());

            private final EmbeddingStore<TextSegment> writer = chooseWriter(pineconeOrMemory);

            private final EmbeddingStore<TextSegment> reader =
                    new com.example.lms.vector.FingerprintAwareEmbeddingStore(
                            (upstash != null && upstash.isConfigured()) ? upstash : writer,
                            embeddingFingerprint,
                            writer,
                            metricRegistry
                    );

            private EmbeddingStore<TextSegment> chooseWriter(EmbeddingStore<TextSegment> candidate) {
                String choice = vectorStoreChoice == null ? "" : vectorStoreChoice.trim().toLowerCase(java.util.Locale.ROOT);
                boolean preferUpstash = "upstash".equals(choice);
                if (upstash != null && upstash.isConfigured() && upstash.isWriteEnabled()) {
                    // Pinecone 미구성(=InMemory fallback)이거나 명시적으로 upstash를 선택한 경우,
                    // writer를 upstash로 두어 재시작 후에도 벡터가 유지되도록 합니다.
                    if (preferUpstash || candidate instanceof InMemoryEmbeddingStore) {
                        return upstash;
                    }
                }
                return candidate;
            }


            private TextSegment stampForWriter(TextSegment embedded) {
                if (embedded == null) return null;
                if (embeddingFingerprint == null) return embedded;
                try {
                    java.util.Map<String, Object> base = new java.util.LinkedHashMap<>();
                    if (embedded.metadata() != null) base.putAll(embedded.metadata().toMap());
                    base.put(com.example.lms.vector.EmbeddingFingerprint.META_EMB_FP, embeddingFingerprint.fingerprint());
                    base.put(com.example.lms.vector.EmbeddingFingerprint.META_EMB_ID, embeddingFingerprint.embId());
                    base.put(com.example.lms.vector.EmbeddingFingerprint.META_EMB_PROVIDER, embeddingFingerprint.provider());
                    base.put(com.example.lms.vector.EmbeddingFingerprint.META_EMB_MODEL, embeddingFingerprint.model());
                    base.put(com.example.lms.vector.EmbeddingFingerprint.META_EMB_DIM, embeddingFingerprint.dimensions());
                    return TextSegment.from(embedded.text(), dev.langchain4j.data.document.Metadata.from(base));
                } catch (Exception ignore) {
                    return embedded;
                }
            }

            private void mirrorToUpstashFailSoft(String id, Embedding embedding, TextSegment embedded) {
                // writer 자체가 upstash면 중복 upsert 방지
                if (writer == upstash) return;
                if (id == null || id.isBlank()) return;
                if (embedding == null || embedded == null) return;
                try {
                    if (upstash != null && upstash.isWriteEnabled()) {
                        upstash.add(id, embedding, embedded);
                    }
                } catch (Exception ignore) { }
            }

            // Delegate single vector additions to the writer (Pinecone). The
            // Upstash adapter always operates in read-only mode when the
            // API key is omitted, but delegating writes exclusively avoids
            // accidental upserts when read-only tokens are used.
            @Override
            public String add(Embedding embedding) {
                return writer.add(embedding);
            }

            @Override
            public void add(String id, Embedding embedding) {
                writer.add(id, embedding);
            }

            @Override
            public String add(Embedding embedding, TextSegment embedded) {
                TextSegment stamped = stampForWriter(embedded);
                String id = writer.add(embedding, stamped);
                // Upstash에도 fail-soft로 미러링 (옵트인)
                mirrorToUpstashFailSoft(id, embedding, stamped);
                return id;
            }

@Override
            public List<String> addAll(List<Embedding> embeddings) {
                return writer.addAll(embeddings);
            }

            @Override
            public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
                try {
                    if (segments == null || segments.isEmpty()) {
                        return writer.addAll(embeddings, segments);
                    }
                    List<TextSegment> stamped = new java.util.ArrayList<>(segments.size());
                    for (TextSegment s : segments) {
                        stamped.add(stampForWriter(s));
                    }
                    List<String> ids = writer.addAll(embeddings, stamped);

                    // Upstash mirror (fail-soft, opt-in)
                    if (ids != null && !ids.isEmpty() && upstash != null && upstash.isWriteEnabled()) {
                        int n = Math.min(ids.size(), Math.min(embeddings == null ? 0 : embeddings.size(), stamped.size()));
                        for (int i = 0; i < n; i++) {
                            mirrorToUpstashFailSoft(ids.get(i), embeddings.get(i), stamped.get(i));
                        }
                    }

                    return ids;
                } catch (Exception e) {
                    // Fail-soft: log and ignore vector upsert errors
                    log.warn("vector upsert degraded: {}", e.toString());
                    return java.util.List.of();
                }
            }


            @Override
            public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
                try {
                    if (ids == null || embeddings == null) return;

                    // Allow stores that can handle null/empty segments (some callers may only persist vectors).
                    if (segments == null || segments.isEmpty()) {
                        writer.addAll(ids, embeddings, segments);
                        return;
                    }

                    int n = Math.min(ids.size(), Math.min(embeddings.size(), segments.size()));
                    if (n <= 0) return;

                    List<String> idSub = (ids.size() == n) ? ids : ids.subList(0, n);
                    List<Embedding> embSub = (embeddings.size() == n) ? embeddings : embeddings.subList(0, n);

                    List<TextSegment> stamped = new java.util.ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        stamped.add(stampForWriter(segments.get(i)));
                    }

                    try {
                        // Primary write path with stable ids.
                        writer.addAll(idSub, embSub, stamped);

                        // Upstash mirror (fail-soft, opt-in).
                        if (upstash != null && upstash.isWriteEnabled()) {
                            for (int i = 0; i < n; i++) {
                                mirrorToUpstashFailSoft(idSub.get(i), embSub.get(i), stamped.get(i));
                            }
                        }
                    } catch (dev.langchain4j.exception.UnsupportedFeatureException ufe) {
                        // Fallback: writer does not support stable-id addAll; use generated ids.
                        log.warn("vector upsert degraded: writer does not support addAll(ids,...); falling back");

                        List<String> genIds = writer.addAll(embSub, stamped);
                        if (genIds != null && !genIds.isEmpty() && upstash != null && upstash.isWriteEnabled()) {
                            int m = Math.min(genIds.size(), Math.min(embSub.size(), stamped.size()));
                            for (int i = 0; i < m; i++) {
                                mirrorToUpstashFailSoft(genIds.get(i), embSub.get(i), stamped.get(i));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("vector upsert degraded (stable-ids): {}", e.toString());
                }
            }

            @Override
            public EmbeddingSearchResult<TextSegment> search(
                    dev.langchain4j.store.embedding.EmbeddingSearchRequest request) {
                try {
                    EmbeddingSearchResult<TextSegment> result = reader.search(request);

                    // If Upstash is not configured or returns no matches, fall back to the writer store.
                    if (result == null || result.matches() == null || result.matches().isEmpty()) {
                        return writer.search(request);
                    }

                    // emb_fp 메타가 전부 누락(legacy)이면 Writer 결과 우선
                    if (embeddingFingerprint != null
                            && looksLikeLegacyFingerprint(result)) {
                        EmbeddingSearchResult<TextSegment> writerRes = writer.search(request);
                        if (writerRes != null && writerRes.matches() != null && !writerRes.matches().isEmpty()) {
                            log.info("[VectorFP] Upstash returned segments without emb_fp; preferring writer results.");
                            return writerRes;
                        }
                    }

                    return result;
                } catch (Exception e) {
                    // Fail-soft: log the error and delegate to Pinecone
                    log.warn("vector query degraded: {}", e.toString());
                    return writer.search(request);
                }
            }

            private boolean looksLikeLegacyFingerprint(EmbeddingSearchResult<TextSegment> result) {
                try {
                    if (result == null || result.matches() == null || result.matches().isEmpty()) {
                        return false;
                    }
                    int inspect = Math.min(8, result.matches().size());
                    int missing = 0;
                    for (int i = 0; i < inspect; i++) {
                        dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment> m = result.matches().get(i);
                        if (m == null || m.embedded() == null || m.embedded().metadata() == null) {
                            missing++;
                            continue;
                        }
                        String fp;
                        try {
                            fp = m.embedded().metadata().getString(com.example.lms.vector.EmbeddingFingerprint.META_EMB_FP);
                        } catch (Exception ignored) {
                            fp = null;
                        }
                        if (fp == null || fp.isBlank()) {
                            missing++;
                        }
                    }
                    return missing == inspect;
                } catch (Exception ignored) {
                    return false;
                }
            }
        };
    }

    /* ═════════ 2. 공통 유틸 ═════════ */
    @Bean
    public Analyzer koreanAnalyzer() {
        return new KoreanAnalyzer();
    }

    /* ═════════ 3. 웹-리트리버들 ═════════ */
    @Bean
    public WebSearchRetriever webSearchRetriever(
            com.example.lms.search.provider.WebSearchProvider webSearchProvider,
            PageContentScraper scraper,
            AuthorityScorer authorityScorer,
            com.example.lms.service.rag.filter.GenericDocClassifier genericClassifier,
            com.example.lms.service.rag.detector.GameDomainDetector domainDetector,
            com.example.lms.service.rag.filter.EducationDocClassifier educationClassifier,
            // Inject the CachedWebSearch aggregator to enable multi-provider fan-out.
            com.acme.aicore.adapters.search.CachedWebSearch cachedWebSearch) {
        // topK는 WebSearchRetriever 필드에 @Value 로 주입됨
        return new WebSearchRetriever(
                webSearchProvider,
                cachedWebSearch,
                scraper,
                authorityScorer,
                genericClassifier,
                domainDetector,
                educationClassifier);
    }

    // (선택) 유틸 ChatModel - 기본 chatModel과 함께 존재. 이걸 Primary로 써도 됨.
    @Bean("utilityChatModel")
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel utilityChatModel(@Value("${lms.use-rag:true}") boolean useRagDefault) {
        return OpenAiChatModel.builder()
                .apiKey(openAiKey)
                .modelName(chatModelName)
                .temperature(useRagDefault ? chatTemperature : 0.0)
                .timeout(Duration.ofSeconds(openAiTimeoutSec))
                .build();
    }

    @Bean
    public ReciprocalRankFuser reciprocalRankFuser() {
        return new ReciprocalRankFuser();
    }

    // ── Add: com.acme.aicore.adapters.search.CachedWebSearch bean (out-of-package)
    @Bean
    public CachedWebSearch cachedWebSearch(java.util.List<WebSearchProvider> providers) {
        return new CachedWebSearch(providers); // providers 목록은 없으면 빈 리스트로 주입됨
    }

    @Bean
    public AnalyzeWebSearchRetriever analyzeWebSearchRetriever(
            Analyzer koreanAnalyzer,
            com.example.lms.search.provider.WebSearchProvider webSearchProvider,
            @Value("${search.web-topk:5}") int webTopK,
            QueryContextPreprocessor preprocessor,
            com.example.lms.search.SmartQueryPlanner smartQueryPlanner,
            @Qualifier("searchIoExecutor") java.util.concurrent.ExecutorService searchIoExecutor) {
        // Inject SmartQueryPlanner to enable proper query generation. Passing null
        // causes
        // AnalyzeWebSearchRetriever to fall back to a passthrough plan, which disables
        // morphological analysis and deduplication and leads to poor search results.
        return new AnalyzeWebSearchRetriever(koreanAnalyzer, webSearchProvider, webTopK, preprocessor,
                smartQueryPlanner, searchIoExecutor);
    }

    /**
     * Health indicator bean that exposes the status of the configured embedding
     * store. When the application falls back to an in-memory store due to a
     * remote vector store failure, this indicator will report {@code DOWN} to
     * the health endpoint. When a proper remote store is in use, it reports
     * {@code UP} unless an exception occurs when probing the store.
     */
    @Bean
    public VectorStoreHealthIndicator vectorStoreHealthIndicator(EmbeddingStore<TextSegment> embeddingStore) {
        return new VectorStoreHealthIndicator(embeddingStore);
    }

    /**
     * Health indicator for the LLM configuration. Exposes the configured
     * provider, base URL (masked) and model name via the Actuator health
     * endpoint. Unlike {@link com.example.lms.health.LlmHealth}, this
     * indicator does not perform a live ping but reports the configuration
     * status only.
     */
    @Bean
    public com.example.lms.health.LlmHealthIndicator llmHealthIndicator(org.springframework.core.env.Environment env) {
        return new com.example.lms.health.LlmHealthIndicator(env);
    }

    // ⚠️ LangChainRAGService 는 @Service 로 등록됩니다.
    // 중복 빈 생성을 피하기 위해 수동 @Bean 정의를 제거합니다.

    // NOTE:
    // - HybridRetriever 는 @Component 로 등록됩니다. (여기서 @Bean 만들지 마세요)
    // - RetrievalHandler 는 RetrieverChainConfig 에서만 @Bean 으로 제공합니다.
}
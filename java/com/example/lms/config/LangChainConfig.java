package com.example.lms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import com.example.lms.memory.PersistentChatMemory;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.NaverSearchService;
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
     * Static logger for this configuration.  Using an explicit Logger avoids the need
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
    // Resolve the OpenAI/Groq API key from configuration or environment.  Use
    // the `openai.api.key` property when provided and fall back to the
    // OPENAI_API_KEY or GROQ_API_KEY environment variables otherwise.
    // API key must be supplied via properties; environment fallbacks are intentionally
    // disallowed to enforce explicit configuration and avoid leaking secrets via env vars.
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
     * (e.g. Pinecone) cannot be initialized.  When set to {@code true}, any
     * exception thrown during vector store initialization will be rethrown,
     * causing the application context to fail to start.  When {@code false},
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
    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel chatModel(@Value("${lms.use-rag:true}") boolean useRagDefault) {
        // Fail fast when the OpenAI key is not provided.  This avoids silent fallbacks to
        // environment variables or degraded behaviour, as per the environment isolation policy.
        if (openAiKey == null || openAiKey.isBlank()) {
            throw new IllegalStateException("openai.api.key is missing (ENV fallback disabled by policy)");
        }
        return OpenAiChatModel.builder()
                .apiKey(openAiKey)
                .modelName(chatModelName)
                .temperature(useRagDefault ? chatTemperature : 0.0)
                .timeout(Duration.ofSeconds(openAiTimeoutSec))
                .build();
    }

    /**
     * 추천/조합(RECOMMENDATION) 전용 상위 모델(저온)
     */
    @Bean("moeChatModel")
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel moeChatModel(
            // Default to gpt-4 for the MOE recommender model
            @Value("${openai.chat.model.moe:gpt-4}") String moeModel,
            @Value("${openai.chat.temperature.recommender:0.2}") double recTemp
    ) {
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
                            "embedding.provider=openai 인데 openai.api.key 가 설정되어 있지 않습니다."
                    );
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
                null,                       // null이면 EmbeddingCache.InMemory 사용
                java.time.Duration.ofMinutes(15)
        );
    }

    // LangChainConfig.java
    @Bean
    @ConditionalOnMissingBean(QueryTransformer.class)
    public QueryTransformer queryTransformer(ChatModel llm) {
        return new QueryTransformer(llm);
    }

    @Bean
    @SuppressWarnings({"removal"})
    @ConditionalOnProperty(name = "vector.store", havingValue = "pinecone", matchIfMissing = true) // 기본 pinecone
    @Lazy  // 초기 연결을 최대한 늦춤
    public EmbeddingStore<TextSegment> pineconeEmbeddingStore(PineconeProps p) {
        try {
            return PineconeEmbeddingStore.builder()
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
            return new InMemoryEmbeddingStore<>();
        }
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
     * implements the {@link EmbeddingStore} interface.  It is used as
     * the read path for vector queries while writing operations are
     * delegated to the Pinecone store.  When Upstash is not configured
     * (blank rest URL or API key) the adapter gracefully returns empty
     * results.
     */
    // UpstashVectorStoreAdapter 빈은 FederatedVectorStoreConfig 쪽에서만 정의합니다.
    /**
     * Composite embedding store that routes read and write operations to
     * different backends.  Reads (search) are served from Upstash
     * Vector DB, while writes (add/insert) are delegated to the
     * Pinecone store.  When either backend fails the operation
     * degrades to the other without propagating the exception.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "retrieval.vector.enabled", havingValue = "false", matchIfMissing = false)
    public EmbeddingStore<TextSegment> embeddingStore(
            UpstashVectorStoreAdapter upstash,
            @Qualifier("pineconeEmbeddingStore") EmbeddingStore<TextSegment> pinecone
    ) {
        return new EmbeddingStore<>() {
            // Delegate single vector additions to the writer (Pinecone).  The
            // Upstash adapter always operates in read-only mode when the
            // API key is omitted, but delegating writes exclusively avoids
            // accidental upserts when read-only tokens are used.
            @Override
            public String add(Embedding embedding) {
                return pinecone.add(embedding);
            }
            @Override
            public void add(String id, Embedding embedding) {
                pinecone.add(id, embedding);
            }
            @Override
            public String add(Embedding embedding, TextSegment embedded) {
                return pinecone.add(embedding, embedded);
            }
            @Override
            public List<String> addAll(List<Embedding> embeddings) {
                return pinecone.addAll(embeddings);
            }
            @Override
            public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
                try {
                    return pinecone.addAll(embeddings, segments);
                } catch (Exception e) {
                    // Fail-soft: log and ignore vector upsert errors
                    log.warn("vector upsert degraded: {}", e.toString());
                    return java.util.List.of();
                }
            }
            @Override
            public EmbeddingSearchResult<TextSegment> search(dev.langchain4j.store.embedding.EmbeddingSearchRequest request) {
                try {
                    EmbeddingSearchResult<TextSegment> result = upstash.search(request);
                    return result;
                } catch (Exception e) {
                    // Fail-soft: log the error and delegate to Pinecone
                    log.warn("vector query degraded: {}", e.toString());
                    return pinecone.search(request);
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
            NaverSearchService svc,
            PageContentScraper scraper,
            AuthorityScorer authorityScorer,
            com.example.lms.service.rag.filter.GenericDocClassifier genericClassifier,
            com.example.lms.service.rag.detector.GameDomainDetector domainDetector,
            com.example.lms.service.rag.filter.EducationDocClassifier educationClassifier,
            // Inject the CachedWebSearch aggregator to enable multi-provider fan-out.
            com.acme.aicore.adapters.search.CachedWebSearch cachedWebSearch
    ) {
        // topK는 WebSearchRetriever 필드에 @Value 로 주입됨
        return new WebSearchRetriever(
                svc,
                cachedWebSearch,
                scraper,
                authorityScorer,
                genericClassifier,
                domainDetector,
                educationClassifier
        );
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
            NaverSearchService svc,
            @Value("${search.morph.max-tokens:5}") int maxTokens,
            QueryContextPreprocessor preprocessor,
            com.example.lms.search.SmartQueryPlanner smartQueryPlanner
    ) {
        // Inject SmartQueryPlanner to enable proper query generation.  Passing null causes
        // AnalyzeWebSearchRetriever to fall back to a passthrough plan, which disables
        // morphological analysis and deduplication and leads to poor search results.
        return new AnalyzeWebSearchRetriever(koreanAnalyzer, svc, maxTokens, preprocessor, smartQueryPlanner);
    }

    /**
     * Health indicator bean that exposes the status of the configured embedding
     * store.  When the application falls back to an in-memory store due to a
     * remote vector store failure, this indicator will report {@code DOWN} to
     * the health endpoint.  When a proper remote store is in use, it reports
     * {@code UP} unless an exception occurs when probing the store.
     */
    @Bean
    public VectorStoreHealthIndicator vectorStoreHealthIndicator(EmbeddingStore<TextSegment> embeddingStore) {
        return new VectorStoreHealthIndicator(embeddingStore);
    }

    /**
     * Health indicator for the LLM configuration.  Exposes the configured
     * provider, base URL (masked) and model name via the Actuator health
     * endpoint.  Unlike {@link com.example.lms.health.LlmHealth}, this
     * indicator does not perform a live ping but reports the configuration
     * status only.
     */
    @Bean
    public com.example.lms.health.LlmHealthIndicator llmHealthIndicator(org.springframework.core.env.Environment env) {
        return new com.example.lms.health.LlmHealthIndicator(env);
    }

    // ⚠️ LangChainRAGService 는 @Service 로 등록됩니다.
    //    중복 빈 생성을 피하기 위해 수동 @Bean 정의를 제거합니다.

    // NOTE:
    // - HybridRetriever 는 @Component 로 등록됩니다. (여기서 @Bean 만들지 마세요)
    // - RetrievalHandler 는 RetrieverChainConfig 에서만 @Bean 으로 제공합니다.
}
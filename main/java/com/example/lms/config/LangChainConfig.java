package com.example.lms.config;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import ai.abandonware.nova.orch.llm.ModelGuardSupport;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.llm.ModelRuntimeHealthTracker;
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
import com.example.lms.service.VectorMetaKeys;
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
import com.example.lms.service.vector.VectorIngestProtectionService;
import com.example.lms.service.vector.UpstashVectorStoreAdapter;
import com.example.lms.service.vector.VectorIngestProtectionService;
import com.example.lms.service.soak.metrics.SoakMetricRegistry;
import com.example.lms.search.TraceStore;
import org.springframework.web.reactive.function.client.WebClient;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import com.acme.aicore.adapters.search.CachedWebSearch;
import com.acme.aicore.domain.ports.WebSearchProvider;

// Use SLF4J Logger directly instead of Lombok 

import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore; // fallback??
// Added for Upstash vector store integration
@Configuration
@EnableConfigurationProperties(PineconeProps.class)
public class LangChainConfig {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelRuntimeHealthTracker apiFailureHealthTracker;

    private dev.langchain4j.http.client.HttpClientBuilder apiObservedHttpClientBuilder() {
        return apiFailureHealthTracker == null ? dev.langchain4j.http.client.HttpClientBuilderLoader.loadHttpClientBuilder()
                : apiFailureHealthTracker.observedHttpClientBuilder("primary");
    }
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

    /* ?????????? OpenAI / Pinecone ???살씁???????????? */
    // Resolve the OpenAI/Groq API key from configuration or environment. Use
    // the `openai.api.key` property when provided and fall back to the
    // OPENAI_API_KEY or GROQ_API_KEY environment variables otherwise.
    // API key must be supplied via properties; environment fallbacks are
    // intentionally
    // disallowed to enforce explicit configuration and avoid leaking secrets via
    // env vars.
    @Value("${openai.api.key}")
    private String openAiKey;
    @Value("${openai.chat.model:${llm.chat-model:gemma4:26b}}")
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

    @Value("${embedding.provider:${embeddings.provider:ollama}}")
    private String embeddingProvider;
    @Value("${embeddings.provider:}")
    private String legacyEmbeddingProvider;

    @Value("${embedding.base-url:http://localhost:11434/api/embed}")
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VectorIngestProtectionService ingestProtectionService;

    /* ?????????? Self-Ask ?濡ろ떟?????筌먲퐢六??????????? */
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

    /* ??誘딆궠已??誘딆궠已??誘딆궠已??誘딆궠已??1. LLM / ??ш끽維?????誘딆궠已??誘딆궠已??誘딆궠已??誘딆궠已??*/
    // [REMOVED] chatModel ???? LlmConfig.java?????@Primary???嶺뚮Ĳ?됭린??    // BeanDefinitionOverrideException ?袁⑸젻泳?????ш낄援??濚욌꼬?댄꺇???嶺뚮Ĳ?됭린???癰귙끋源?
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
     * ??⑤베毓???釉뚰????(RECOMMENDATION) ??ш끽維?????ㅼ굣筌?癲ル슢?꾤땟???????
     */
    @Bean("moeChatModel")
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel moeChatModel(
            // Default to gpt-4 for the MOE recommender model
            @Value("${openai.chat.model.moe:gpt-4}") String moeModel,
            @Value("${openai.chat.temperature.recommender:0.2}") double recTemp,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker) {
        String key = usableOpenAiKey();
        if (key == null) {
            return missingOpenAiKeyChatModel(
                    moeModel,
                    "MOE_CHAT(no_api_key)",
                    recTemp,
                    modelRuntimeHealthTracker);
        }
        return OpenAiChatModel.builder()
                .httpClientBuilder(apiObservedHttpClientBuilder())
                .apiKey(key)
                .modelName(moeModel)
                .temperature(recTemp)
                .timeout(Duration.ofSeconds(openAiTimeoutSec))
                .build();
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(OllamaEmbeddingModel ollamaEmbeddingModel,
            com.example.lms.vector.EmbeddingFingerprint embeddingFingerprint) {

        String provider = com.example.lms.vector.EmbeddingFingerprint.resolveProvider(
                embeddingProvider, legacyEmbeddingProvider);

        EmbeddingModel delegate;

        switch (provider) {
            case "openai":
                String key = usableOpenAiKey();
                if (key == null) {
                    throw new IllegalStateException(
                            "[AWX][embedding][openai] disabled reason=missing_openai_api_key");
                }
                delegate = OpenAiEmbeddingModel.builder()
                        .httpClientBuilder(apiObservedHttpClientBuilder())
                        .apiKey(key)
                        .modelName(embeddingModelName)
                        .dimensions(embeddingDimensions)
                        .timeout(Duration.ofSeconds(openAiTimeoutSec))
                        .build();
                break;

            case "none":
                // ??ш끽維??web-only RAG 癲ル슢?꾤땟???(??ш끽維????????濚밸Ŧ遊??
                delegate = new com.example.lms.llm.NoopEmbeddingModel(embeddingDimensions);
                break;

            case "ollama":
                // ??れ삀???筌? ?棺??짆?쏆춾?Ollama /api/embed
                delegate = ollamaEmbeddingModel;
                break;
            case "hf":
                // The legacy HF component has no verified primary endpoint/vector-shape contract.
                throw new IllegalArgumentException("embedding_provider_hf_unsupported");
            default:
                throw new IllegalArgumentException("embedding_provider_unknown");
        }

        // Shared embedding cache/decorator layer.
        return new DecoratingEmbeddingModel(
                delegate,
                null,
                java.time.Duration.ofMinutes(15),
                embeddingFingerprint);
    }

    // LangChainConfig.java
    @Bean
    @ConditionalOnMissingBean(QueryTransformer.class)
    public QueryTransformer queryTransformer(ChatModel llm) {
        return new QueryTransformer(llm);
    }

    @Bean
    @SuppressWarnings({ "removal" })
    @ConditionalOnProperty(name = "vector.store", havingValue = "pinecone", matchIfMissing = false)
    @Lazy
    public EmbeddingStore<TextSegment> pineconeEmbeddingStore(
            PineconeProps p,
            com.example.lms.vector.EmbeddingFingerprint embeddingFingerprint,
            @Qualifier("defaultWebClient") WebClient webClient,
            @Value("${pinecone.timeout-ms:3000}") long timeoutMs) {
        EmbeddingStore<TextSegment> base = new com.example.lms.service.vector.PineconeVectorStoreAdapter(
                webClient, p, embeddingFingerprint, Duration.ofMillis(Math.max(100, Math.min(10000, timeoutMs))));

        // Prevent cross-embedding-model contamination by stamping and filtering using
        // the current embedding fingerprint.
        return new com.example.lms.vector.FingerprintAwareEmbeddingStore(base, embeddingFingerprint);
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingStore.class)
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    /*
     * ??誘딆궠已??誘딆궠已??誘딆궠已??誘딆궠已??1b. Upstash Vector Store (READ-ONLY) ??誘딆궠已??誘딆궠已??誘딆궠已??誘딆궠已??     *
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
            @Value("${vector.store:memory}") String vectorStoreChoice) {
        return new EmbeddingStore<>() {
            private final EmbeddingStore<TextSegment> pineconeOrMemory = pineconeProvider
                    .getIfAvailable(() -> new InMemoryEmbeddingStore<>());

            private final EmbeddingStore<TextSegment> writer = chooseWriter(pineconeOrMemory);

            private final EmbeddingStore<TextSegment> guardedWriter = new com.example.lms.vector.FingerprintAwareEmbeddingStore(
                    writer, embeddingFingerprint);

            private final EmbeddingStore<TextSegment> reader = new com.example.lms.vector.FingerprintAwareEmbeddingStore(
                    (upstash != null && upstash.isConfigured()) ? upstash : writer,
                    embeddingFingerprint,
                    writer,
                    metricRegistry);

            {
                Map<String, Object> mode = upstash == null ? Map.of("configured", false) : upstash.effectiveMode();
                log.info("[AWX2AF2][vector][backend] vectorStore={} upstashMode={} writer={} reader={}",
                        vectorStoreChoice, mode, writer.getClass().getSimpleName(),
                        (upstash != null && upstash.isConfigured()) ? "upstash" : writer.getClass().getSimpleName());
                tracePut("vector.backend.mode", mode.toString());
                tracePut("vector.backend.writer", writer.getClass().getSimpleName());
            }

            private void tracePut(String key, Object value) {
                try {
                    com.example.lms.search.TraceStore.put(key, value);
                } catch (Exception ignore) {
                    traceSuppressed(key);
                }
            }

            private void traceInc(String key) {
                try {
                    com.example.lms.search.TraceStore.inc(key);
                } catch (Exception ignore) {
                    traceSuppressed(key);
                }
            }

            private void traceSuppressed(String stage) {
                TraceStore.put("vector.suppressed." + traceStage(stage), true);
                log.debug("[LangChainConfig][trace] suppressed stage={}", traceStage(stage));
            }

            private String traceStage(String stage) {
                String label = SafeRedactor.traceLabel(stage);
                return (label == null || label.isBlank()) ? "unknown" : label;
            }

            private EmbeddingStore<TextSegment> chooseWriter(EmbeddingStore<TextSegment> candidate) {
                String choice = vectorStoreChoice == null ? ""
                        : vectorStoreChoice.trim().toLowerCase(java.util.Locale.ROOT);
                boolean preferUpstash = "upstash".equals(choice);
                if (upstash != null && upstash.isConfigured() && upstash.isWriteEnabled()) {
                    // Pinecone 雅?퍔瑗????=InMemory fallback)????됯뭅??癲ル슢?뤸뤃????ㅼ굣筌뤿뱶??upstash?????ャ뀕????濡ろ뜑???
                    // writer??upstash??????????????ш끽維????類?뺨??щ빝影?놁씀? ?????嚥▲꺃?????筌뤾퍓???
                    if (preferUpstash || candidate instanceof InMemoryEmbeddingStore) {
                        return upstash;
                    }
                }
                return candidate;
            }

            private TextSegment stampForWriter(TextSegment embedded) {
                if (embedded == null)
                    return null;
                if (embeddingFingerprint == null)
                    return embedded;
                try {
                    java.util.Map<String, Object> base = new java.util.LinkedHashMap<>();
                    if (embedded.metadata() != null)
                        base.putAll(embedded.metadata().toMap());
                    base.put(com.example.lms.vector.EmbeddingFingerprint.META_EMB_FP,
                            embeddingFingerprint.fingerprint());
                    base.put(com.example.lms.vector.EmbeddingFingerprint.META_EMB_ID, embeddingFingerprint.embId());
                    base.put(com.example.lms.vector.EmbeddingFingerprint.META_EMB_PROVIDER,
                            embeddingFingerprint.provider());
                    base.put(com.example.lms.vector.EmbeddingFingerprint.META_EMB_MODEL, embeddingFingerprint.model());
                    base.put(com.example.lms.vector.EmbeddingFingerprint.META_EMB_DIM,
                            embeddingFingerprint.dimensions());
                    return TextSegment.from(embedded.text(), dev.langchain4j.data.document.Metadata.from(base));
                } catch (Exception ignore) {
                    traceSuppressed("vector.stampForWriter");
                    return embedded;
                }
            }

            private void mirrorToUpstashFailSoft(String id, Embedding embedding, TextSegment embedded) {
                // writer ????瓘琉??쎛 upstash癲?濚욌꼬?댄꺇??upsert ?袁⑸젻泳?
                if (writer == upstash)
                    return;
                if (id == null || id.isBlank())
                    return;
                if (embedding == null || embedded == null)
                    return;
                try {
                    if (upstash != null && upstash.isWriteEnabled()) {
                        upstash.add(id, embedding, embedded);
                    }
                } catch (Exception ignore) {
                    traceSuppressed("vector.mirrorToUpstash");
                }
            }


            private boolean strictWrite(java.util.List<TextSegment> segments) {
                // A selected persistent backend must never report a failed write as persisted.
                if ("pinecone".equalsIgnoreCase(vectorStoreChoice)) return true;
                try {
                    if (segments == null || segments.isEmpty()) {
                        return false;
                    }
                    for (TextSegment s : segments) {
                        if (s == null || s.metadata() == null) {
                            continue;
                        }
                        Object v = s.metadata().toMap().get(VectorMetaKeys.META_STRICT_WRITE);
                        if (v == null) {
                            continue;
                        }
                        String sv = String.valueOf(v);
                        if ("true".equalsIgnoreCase(sv) || "1".equals(sv)) {
                            return true;
                        }
                    }
                } catch (Exception ignore) {
                    traceSuppressed("vector.strictWrite");
                }
                return false;
            }

            private String sidSample(java.util.List<TextSegment> segments) {
                try {
                    if (segments == null || segments.isEmpty()) {
                        return "";
                    }
                    for (TextSegment s : segments) {
                        if (s == null || s.metadata() == null) {
                            continue;
                        }
                        java.util.Map<String, Object> m = s.metadata().toMap();
                        if (m == null || m.isEmpty()) {
                            continue;
                        }
                        Object v = m.get(VectorMetaKeys.META_SID);
                        if (v == null) {
                            v = m.get(VectorMetaKeys.META_SID_LOGICAL);
                        }
                        if (v != null) {
                            return String.valueOf(v);
                        }
                    }
                } catch (Exception ignore) {
                    traceSuppressed("vector.sidSample");
                }
                return "";
            }


            // Delegate single vector additions to the writer (Pinecone). The
            // Upstash adapter always operates in read-only mode when the
            // API key is omitted, but delegating writes exclusively avoids
            // accidental upserts when read-only tokens are used.
            @Override
            public String add(Embedding embedding) {
                if (embedding == null) {
                    traceInc("vector.skip.nullEmbedding");
                    return null;
                }
                if (embedding.vector() == null || embedding.vector().length == 0) {
                    traceInc("vector.skip.emptyVector");
                    return null;
                }
                return writer.add(embedding);
            }

            @Override
            public void add(String id, Embedding embedding) {
                if (id == null) {
                    traceInc("vector.skip.blankId");
                    return;
                }
                String cleanId = id.trim();
                if (cleanId.isEmpty()) {
                    traceInc("vector.skip.blankId");
                    return;
                }
                if (embedding == null || embedding.vector() == null || embedding.vector().length == 0) {
                    traceInc("vector.skip.emptyVector");
                    return;
                }
                writer.add(cleanId, embedding);
            }

            @Override
            public String add(Embedding embedding, TextSegment embedded) {
                TextSegment stamped = stampForWriter(embedded);
                String id = writer.add(embedding, stamped);
                // Upstash???筌?fail-soft??雅?퍔瑗띰㎖??壤?(???獄??
                mirrorToUpstashFailSoft(id, embedding, stamped);
                return id;
            }

            @Override
            public List<String> addAll(List<Embedding> embeddings) {
                if (embeddings == null || embeddings.isEmpty()) {
                    return java.util.List.of();
                }
                java.util.List<Embedding> embOk = new java.util.ArrayList<>(embeddings.size());
                for (Embedding e : embeddings) {
                    if (e == null) {
                        traceInc("vector.skip.nullEmbedding");
                        continue;
                    }
                    if (e.vector() == null || e.vector().length == 0) {
                        traceInc("vector.skip.emptyVector");
                        continue;
                    }
                    embOk.add(e);
                }
                if (embOk.isEmpty()) {
                    return java.util.List.of();
                }
                return writer.addAll(embOk);
            }

            @Override
            public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
                try {
                    if (embeddings == null || embeddings.isEmpty()) {
                        return java.util.List.of();
                    }

                    // Allow stores that can handle null/empty segments (some callers may only
                    // persist vectors).
                    if (segments == null || segments.isEmpty()) {
                        java.util.List<Embedding> embOk = new java.util.ArrayList<>(embeddings.size());
                        for (Embedding e : embeddings) {
                            if (e == null) {
                                traceInc("vector.skip.nullEmbedding");
                                continue;
                            }
                            if (e.vector() == null || e.vector().length == 0) {
                                traceInc("vector.skip.emptyVector");
                                continue;
                            }
                            embOk.add(e);
                        }
                        if (embOk.isEmpty()) {
                            return java.util.List.of();
                        }
                        return writer.addAll(embOk, segments);
                    }

                    int n = Math.min(embeddings.size(), segments.size());
                    if (n <= 0) {
                        return java.util.List.of();
                    }

                    // Filter invalid vectors (empty embedding) and null segments to avoid
                    // PineconeValidationException-like failures.
                    java.util.List<Embedding> embOk = new java.util.ArrayList<>(n);
                    java.util.List<TextSegment> segOk = new java.util.ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        Embedding e = embeddings.get(i);
                        if (e == null) {
                            traceInc("vector.skip.nullEmbedding");
                            continue;
                        }
                        if (e.vector() == null || e.vector().length == 0) {
                            traceInc("vector.skip.emptyVector");
                            continue;
                        }
                        TextSegment s = segments.get(i);
                        if (s == null) {
                            traceInc("vector.skip.nullSegment");
                            continue;
                        }
                        embOk.add(e);
                        segOk.add(s);
                    }

                    if (embOk.isEmpty() || segOk.isEmpty()) {
                        return java.util.List.of();
                    }

                    java.util.List<TextSegment> stamped = new java.util.ArrayList<>(segOk.size());
                    for (TextSegment s : segOk) {
                        stamped.add(stampForWriter(s));
                    }

                    java.util.List<String> ids = writer.addAll(embOk, stamped);

                    // Upstash mirror (fail-soft, opt-in)
                    if (ids != null && !ids.isEmpty() && upstash != null && upstash.isWriteEnabled()) {
                        int m = Math.min(ids.size(), Math.min(embOk.size(), stamped.size()));
                        for (int i = 0; i < m; i++) {
                            mirrorToUpstashFailSoft(ids.get(i), embOk.get(i), stamped.get(i));
                        }
                    }

                    return ids;
                } catch (Exception e) {
                    traceSuppressed("vector.upsert");
                    // [INGEST_PROTECTION] Feed upsert failures into the quarantine detector (fail-soft).
                    try {
                        if (ingestProtectionService != null) {
                            ingestProtectionService.recordIfMatches(sidSample(segments), e, "vector_upsert");
                        }
                    } catch (Exception ignore) {
                        traceSuppressed("vector.ingestProtection");
                        // fail-soft
                    }
                    boolean strict = strictWrite(segments);
                    if (strict) {
                        log.error("[VectorStore][STRICT] vector upsert failed. errorHash={} errorLength={}",
                                SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                        throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                    }
                    // Fail-soft: log and ignore vector upsert errors
                    log.warn("vector upsert degraded. errorHash={} errorLength={}",
                            SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                    return java.util.List.of();
                }
            }

            @Override
            public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
                try {
                    if (ids == null || embeddings == null)
                        return;

                    boolean segmentsEmpty = (segments == null || segments.isEmpty());

                    int n0 = Math.min(ids.size(), embeddings.size());
                    int n = segmentsEmpty ? n0 : Math.min(n0, segments.size());
                    if (n <= 0)
                        return;

                    // Filter invalid ids / empty vectors to avoid vector-store validation failures.
                    java.util.List<String> idOk = new java.util.ArrayList<>(n);
                    java.util.List<Embedding> embOk = new java.util.ArrayList<>(n);
                    java.util.List<TextSegment> segOk = segmentsEmpty ? null : new java.util.ArrayList<>(n);

                    for (int i = 0; i < n; i++) {
                        String id = ids.get(i);
                        if (id == null) {
                            traceInc("vector.skip.blankId");
                            continue;
                        }
                        String cleanId = id.trim();
                        if (cleanId.isEmpty()) {
                            traceInc("vector.skip.blankId");
                            continue;
                        }

                        Embedding e = embeddings.get(i);
                        if (e == null) {
                            traceInc("vector.skip.nullEmbedding");
                            continue;
                        }
                        if (e.vector() == null || e.vector().length == 0) {
                            traceInc("vector.skip.emptyVector");
                            continue;
                        }

                        if (!segmentsEmpty) {
                            TextSegment s = segments.get(i);
                            if (s == null) {
                                traceInc("vector.skip.nullSegment");
                                continue;
                            }
                            segOk.add(s);
                        }

                        idOk.add(cleanId);
                        embOk.add(e);
                    }

                    if (idOk.isEmpty() || embOk.isEmpty())
                        return;

                    // Allow stores that can handle null/empty segments (some callers may only
                    // persist vectors).
                    if (segmentsEmpty) {
                        writer.addAll(idOk, embOk, segments);
                        return;
                    }

                    java.util.List<TextSegment> stamped = new java.util.ArrayList<>(segOk.size());
                    for (TextSegment s : segOk) {
                        stamped.add(stampForWriter(s));
                    }

                    try {
                        // Primary write path with stable ids.
                        writer.addAll(idOk, embOk, stamped);

                        // Upstash mirror (fail-soft, opt-in).
                        if (upstash != null && upstash.isWriteEnabled()) {
                            int m = Math.min(idOk.size(), Math.min(embOk.size(), stamped.size()));
                            for (int i = 0; i < m; i++) {
                                mirrorToUpstashFailSoft(idOk.get(i), embOk.get(i), stamped.get(i));
                            }
                        }
                    } catch (dev.langchain4j.exception.UnsupportedFeatureException ufe) {
                        // Fallback: writer does not support stable-id addAll; use generated ids.
                        log.warn("vector upsert degraded: writer does not support addAll(ids,...); falling back");

                        java.util.List<String> genIds = writer.addAll(embOk, stamped);
                        if (genIds != null && !genIds.isEmpty() && upstash != null && upstash.isWriteEnabled()) {
                            int m = Math.min(genIds.size(), Math.min(embOk.size(), stamped.size()));
                            for (int i = 0; i < m; i++) {
                                mirrorToUpstashFailSoft(genIds.get(i), embOk.get(i), stamped.get(i));
                            }
                        }
                    }
                } catch (Exception e) {
                    traceSuppressed("vector.upsertStableIds");
                    // [INGEST_PROTECTION] Feed stable-id upsert failures into the quarantine detector (fail-soft).
                    try {
                        if (ingestProtectionService != null) {
                            ingestProtectionService.recordIfMatches(sidSample(segments), e, "vector_upsert_stable_ids");
                        }
                    } catch (Exception ignore) {
                        traceSuppressed("vector.ingestProtectionStableIds");
                        // fail-soft
                    }
                    boolean strict = strictWrite(segments);
                    if (strict) {
                        log.error("[VectorStore][STRICT] vector upsert failed (stable-ids). errorHash={} errorLength={}",
                                SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                        throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                    }
                    log.warn("vector upsert degraded (stable-ids). errorHash={} errorLength={}",
                            SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                }
            }

            @Override
            public EmbeddingSearchResult<TextSegment> search(
                    dev.langchain4j.store.embedding.EmbeddingSearchRequest request) {
                try {
                    EmbeddingSearchResult<TextSegment> result = reader.search(request);

                    // If Upstash is not configured or returns no matches, fall back to the writer
                    // store.
                    if (result == null || result.matches() == null || result.matches().isEmpty()) {
                        return upstash != null && upstash.isConfigured() ? guardedWriter.search(request) : result;
                    }

                    // emb_fp 癲ル슢?????좊읈? ??? ??ш끽維곲?legacy)?????Writer ?濡ろ뜏??????Β?띾쭡
                    if (embeddingFingerprint != null
                            && looksLikeLegacyFingerprint(result)) {
                        EmbeddingSearchResult<TextSegment> writerRes = guardedWriter.search(request);
                        if (writerRes != null && writerRes.matches() != null && !writerRes.matches().isEmpty()) {
                            log.info("[VectorFP] Upstash returned segments without emb_fp; preferring writer results.");
                            return writerRes;
                        }
                    }

                    return result;
                } catch (Exception e) {
                    // Fail-soft: log the error and delegate to Pinecone
                    log.warn("vector query degraded. errorHash={} errorLength={}",
                            SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                    if (upstash != null && upstash.isConfigured()) return guardedWriter.search(request);
                    return new EmbeddingSearchResult<>(List.of());
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
                            fp = m.embedded().metadata()
                                    .getString(com.example.lms.vector.EmbeddingFingerprint.META_EMB_FP);
                        } catch (Exception ignored) {
                            traceSuppressed("vector.metadataFingerprint");
                            fp = null;
                        }
                        if (fp == null || fp.isBlank()) {
                            missing++;
                        }
                    }
                    return missing == inspect;
                } catch (Exception ignored) {
                    traceSuppressed("vector.legacyFingerprintCheck");
                    return false;
                }
            }
        };
    }

    /* ??誘딆궠已??誘딆궠已??誘딆궠已??誘딆궠已??2. ???살씁?????ャ뀖????誘딆궠已??誘딆궠已??誘딆궠已??誘딆궠已??*/
    @Bean
    public Analyzer koreanAnalyzer() {
        return new KoreanAnalyzer();
    }

    /* ??誘딆궠已??誘딆궠已??誘딆궠已??誘딆궠已??3. ???域밸Ŧ肉ヨキ?域밸Ŧ留?????誘딆궠已??誘딆궠已??誘딆궠已??誘딆궠已??*/
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
        // topK is injected through WebSearchRetriever fields.
        return new WebSearchRetriever(
                webSearchProvider,
                cachedWebSearch,
                scraper,
                authorityScorer,
                genericClassifier,
                domainDetector,
                educationClassifier);
    }

    // (???ャ뀕?? ???ャ뀖??ChatModel - ??れ삀???chatModel????影?얠맽 ?釉뚰??? ????볥윞 Primary??????닳뵣 ??
    @Bean("utilityChatModel")
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel utilityChatModel(
            @Value("${lms.use-rag:true}") boolean useRagDefault,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker) {
        String key = usableOpenAiKey();
        if (key == null) {
            return missingOpenAiKeyChatModel(
                    chatModelName,
                    "UTILITY_CHAT(no_api_key)",
                    useRagDefault ? chatTemperature : 0.0d,
                    modelRuntimeHealthTracker);
        }
        return OpenAiChatModel.builder()
                .httpClientBuilder(apiObservedHttpClientBuilder())
                .apiKey(key)
                .modelName(chatModelName)
                .temperature(useRagDefault ? chatTemperature : 0.0)
                .timeout(Duration.ofSeconds(openAiTimeoutSec))
                .build();
    }

    public ChatModel moeChatModel(String moeModel, double recTemp) {
        return moeChatModel(moeModel, recTemp, new ModelRuntimeHealthTracker());
    }

    public ChatModel utilityChatModel(boolean useRagDefault) {
        return utilityChatModel(useRagDefault, new ModelRuntimeHealthTracker());
    }

    @Bean
    public ReciprocalRankFuser reciprocalRankFuser() {
        return new ReciprocalRankFuser();
    }

    // ???? Add: com.acme.aicore.adapters.search.CachedWebSearch bean (out-of-package)
    @Bean
    public CachedWebSearch cachedWebSearch(java.util.List<WebSearchProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            log.info("[AWX][search][cached] supplementalProviders=0 disabledReason=supplemental_multi_search_providers_disabled primarySearch=hybrid multiSearch=no-op");
        } else {
            try {
                log.info("[Wiring] CachedWebSearch providers: {}", providers.stream().map(WebSearchProvider::id).toList());
            } catch (Exception ignore) {
                log.info("[Wiring] CachedWebSearch providers wired (count={})", providers.size());
            }
        }
        return new CachedWebSearch(providers);
    }

    private String usableOpenAiKey() {
        return ConfigValueGuards.isMissing(openAiKey) ? null : openAiKey.trim();
    }

    private ChatModel missingOpenAiKeyChatModel(
            String modelName,
            String actionTaken,
            double temperature,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker) {
        String message = ModelGuardSupport.buildExpectedFailureMessage(
                modelName,
                "/v1/chat/completions",
                actionTaken);
        Map<String, Object> ownedOptions = new java.util.LinkedHashMap<>();
        ownedOptions.put("temperature", temperature);
        ownedOptions.put("timeoutMs", Math.max(1L, openAiTimeoutSec) * 1_000L);
        ownedOptions.put("fallbackEnabled", false);
        ModelRuntimeHealthTracker.ExpectedFailureAttemptEvidence attemptEvidence =
                modelRuntimeHealthTracker == null
                        ? null
                        : modelRuntimeHealthTracker.expectedFailureAttemptEvidence(
                                "primary",
                                modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                                        "utility_chat_model",
                                        modelName,
                                        "https://api.openai.com/v1",
                                        "openai_chat_completions"),
                                ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                                        "openai",
                                        modelName,
                                        "openai_chat_completions",
                                        ownedOptions));
        return new ExpectedFailureChatModel(
                message,
                SafeRedactor.hashValue(ModelGuardSupport.canonicalModelName(modelName)),
                attemptEvidence);
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    // NOTE: AnalyzeWebSearchRetriever is annotated with @Component and
    // @RequiredArgsConstructor.
    // It will be auto-configured by Spring and does not require a manual @Bean
    // definition here.
    // Removed conflicting @Bean method that was causing constructor mismatch
    // errors.

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

    // ???レ탴??LangChainRAGService ??@Service ???濚밸Ŧ援욃ㅇ??筌뤾퍓???
    // 濚욌꼬?댄꺇??????獄쏅똻??????紐꺿봼????ш낄援????嚥▲꺃彛?@Bean ?嶺뚮Ĳ?됭린????癰귙끋源??筌뤾퍓???

    // NOTE:
    // - HybridRetriever ??@Component ???濚밸Ŧ援욃ㅇ??筌뤾퍓??? (?????@Bean 癲ル슢???????? 癲ル슢???삳빝??
    // - RetrievalHandler ??RetrieverChainConfig ????節뉗땡?@Bean ???⑥????癰궽블뀬??筌뤾퍓???
}

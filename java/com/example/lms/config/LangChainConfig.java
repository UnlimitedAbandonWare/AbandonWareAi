package com.example.lms.config;
import lombok.extern.slf4j.Slf4j;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;

import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore; // fallback용
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
import com.example.lms.service.rag.auth.AuthorityScorer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.lms.service.rag.extract.PageContentScraper;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import java.time.Duration;
@Slf4j
@Configuration
@EnableConfigurationProperties(PineconeProps.class)
public class LangChainConfig {

    private final ChatMessageRepository msgRepo;
    private final ChatSessionRepository sesRepo;

    public LangChainConfig(ChatMessageRepository msgRepo, ChatSessionRepository sesRepo) {
        this.msgRepo = msgRepo;
        this.sesRepo = sesRepo;
    }

    /* ───── OpenAI / Pinecone 공통 ───── */
    @Value("${openai.api.key}")
    private String openAiKey;
    @Value("${openai.chat.model:gpt-3.5-turbo}")
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

    @Value("${pinecone.embedding-model:text-embedding-3-small}")
    private String embeddingModelName;

    /* ───── Self-Ask 검색 튜닝 ───── */
    @Value("${search.selfask.max-depth:2}")
    private int selfAskMaxDepth;
    @Value("${search.selfask.web-top-k:5}")
    private int selfAskWebTopK;
    @Value("${search.selfask.overall-top-k:10}")
    private int selfAskOverallTopK;

    @Bean("persistentChatMemoryProvider")
    public ChatMemoryProvider persistentChatMemoryProvider() {
        return id -> new PersistentChatMemory(id.toString(), msgRepo, sesRepo);
    }

    /* ═════════ 1. LLM / 임베딩 ═════════ */
    @Bean
    public ChatModel chatModel(@Value("${lms.use-rag:true}") boolean useRagDefault) {
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
    public ChatModel moeChatModel(
            @Value("${openai.chat.model.moe:gpt-4o}") String moeModel,
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
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(openAiKey)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(openAiTimeoutSec))
                .build();
    }

    @Bean
    public QueryTransformer queryTransformer(ChatModel llm) {
        // ChatModel 타입으로 완화하여 OpenAiChatModel/프록시 모두 수용
        return new QueryTransformer(llm);
    }

    @Bean
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
            log.warn("Pinecone init failed; falling back to InMemoryEmbeddingStore", t);
            return new InMemoryEmbeddingStore<>();
        }
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingStore.class)
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
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
            AuthorityScorer authorityScorer
    ) {
        // topK는 WebSearchRetriever 필드에 @Value 로 주입됨
        return new WebSearchRetriever(svc, scraper, authorityScorer);
    }

    // (선택) 유틸 ChatModel — 기본 chatModel과 함께 존재. 이걸 Primary로 써도 됨.
    @Bean("utilityChatModel")
    @Primary
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

    @Bean
    public AnalyzeWebSearchRetriever analyzeWebSearchRetriever(
            Analyzer koreanAnalyzer,
            NaverSearchService svc,
            @Value("${search.morph.max-tokens:5}") int maxTokens,
            QueryContextPreprocessor preprocessor
    ) {
        return new AnalyzeWebSearchRetriever(koreanAnalyzer, svc, maxTokens, preprocessor);
    }

    // ⚠️ LangChainRAGService 는 @Service 로 등록됩니다.
    //    중복 빈 생성을 피하기 위해 수동 @Bean 정의를 제거합니다.

    // NOTE:
    // - HybridRetriever 는 @Component 로 등록됩니다. (여기서 @Bean 만들지 마세요)
    // - RetrievalHandler 는 RetrieverChainConfig 에서만 @Bean 으로 제공합니다.
}

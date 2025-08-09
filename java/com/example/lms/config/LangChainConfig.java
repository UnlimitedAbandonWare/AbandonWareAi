package com.example.lms.config;

import com.example.lms.memory.PersistentChatMemory;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.NaverSearchService;
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
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class LangChainConfig {

    private final ChatMessageRepository msgRepo;
    private final ChatSessionRepository sesRepo;

    public LangChainConfig(ChatMessageRepository msgRepo, ChatSessionRepository sesRepo) {
        this.msgRepo = msgRepo;
        this.sesRepo = sesRepo;
    }

    /* ───── OpenAI / Pinecone 공통 ───── */
    @Value("${openai.api.key}")       private String openAiKey;
    @Value("${openai.chat.model:gpt-3.5-turbo}") private String chatModelName;
    @Value("${openai.chat.temperature:0.7}")     private double chatTemperature;
    @Value("${openai.timeout-seconds:60}")       private long openAiTimeoutSec;

    @Value("${pinecone.api.key}")     private String pcKey;
    @Value("${pinecone.environment}") private String pcEnv;
    @Value("${pinecone.project.id}")  private String pcProjectId;
    @Value("${pinecone.index.name}")  private String pcIndex;

    @Value("${pinecone.embedding-model:text-embedding-3-small}")
    private String embeddingModelName;

    /* ───── Self-Ask 검색 튜닝 ───── */
    @Value("${search.selfask.max-depth:2}")      private int selfAskMaxDepth;
    @Value("${search.selfask.web-top-k:5}")      private int selfAskWebTopK;
    @Value("${search.selfask.overall-top-k:10}") private int selfAskOverallTopK;

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

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(openAiKey)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(openAiTimeoutSec))
                .build();
    }

    @Bean
    public QueryTransformer queryTransformer(OpenAiChatModel llm) {
        return new QueryTransformer(llm);
    }

    /* ① 기본 네임스페이스 */
    @SuppressWarnings("removal") // Pinecone SDK 교체 전까지 임시 억제
    @Bean
    @Primary
    public EmbeddingStore<TextSegment> embeddingStore() {
        return PineconeEmbeddingStore.builder()
                .apiKey(pcKey)
                .environment(pcEnv)
                .projectId(pcProjectId)
                .index(pcIndex)
                .build();
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
            @Value("${search.web.top-k:3}") int topK) {
        return new WebSearchRetriever(svc, topK);
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
            @Value("${search.morph.max-tokens:5}") int maxTokens) {
        return new AnalyzeWebSearchRetriever(koreanAnalyzer, svc, maxTokens);
    }

    /* ═════════ 4. 벡터-RAG 서비스 ═════════ */
    @Bean
    public LangChainRAGService langChainRAGService(
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> store,
            MemoryReinforcementService memorySvc
    ) {
        return new LangChainRAGService(chatModel, embeddingModel, store, memorySvc);
    }

    // NOTE:
    // - HybridRetriever 는 @Component 로 등록됩니다. (여기서 @Bean 만들지 마세요)
    // - RetrievalHandler 는 RetrieverChainConfig 에서만 @Bean 으로 제공합니다.
}

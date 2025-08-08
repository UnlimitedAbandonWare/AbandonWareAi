package com.example.lms.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;           // 🔹 인터페이스

import dev.langchain4j.model.openai.OpenAiChatModel;   // 구현체
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import com.example.lms.matrix.MatrixTransformer;
import com.example.lms.transform.QueryTransformer;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import dev.langchain4j.model.chat.ChatModel;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.QueryComplexityGate;   // ✅ 추가
import com.example.lms.service.rag.LangChainRAGService;

import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.handler.RetrievalHandler;   // ★ 추가
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import com.example.lms.memory.PersistentChatMemory;
import lombok.RequiredArgsConstructor;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class LangChainConfig {

    private final ChatMessageRepository msgRepo;
    private final ChatSessionRepository sesRepo;

    /* ───── OpenAI / Pinecone 공통 ───── */
    @Value("${openai.api.key}")       private String openAiKey;

    @Value("${openai.chat.model:gpt-3.5-turbo}")
    private String chatModelName;

    @Value("${openai.chat.temperature:0.7}")
    private double chatTemperature;

    @Value("${openai.timeout-seconds:60}")
    private long openAiTimeoutSec;

    @Value("${pinecone.api.key}")     private String pcKey;
    @Value("${pinecone.environment}") private String pcEnv;
    @Value("${pinecone.project.id}")  private String pcProjectId;
    @Value("${pinecone.index.name}")  private String pcIndex;

    @Value("${pinecone.embedding-model:text-embedding-3-small}")
    private String embeddingModelName;

    /* ───── Self-Ask 검색 튜닝 ───── */
    @Value("${search.selfask.max-depth:2}")     private int selfAskMaxDepth;
    @Value("${search.selfask.web-top-k:5}")     private int selfAskWebTopK;
    @Value("${search.selfask.overall-top-k:10}")private int selfAskOverallTopK;

    @Bean("persistentChatMemoryProvider")
    public ChatMemoryProvider persistentChatMemoryProvider() {
        return id -> new PersistentChatMemory(id.toString(), msgRepo, sesRepo);
    }

    /* ═════════ 1. LLM / 임베딩 ═════════ */
    @Bean
    public ChatModel chatModel(
            @Value("${lms.use-rag:true}") boolean useRagDefault) {
        return OpenAiChatModel.builder()
                .apiKey(openAiKey)
                .modelName(chatModelName)          // 프로퍼티 적용
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
            MemoryReinforcementService memorySvc) {
        return new LangChainRAGService(chatModel, embeddingModel, store, memorySvc);
    }

    /* ═════════ 5. HybridRetriever ═════════ */
    @Bean
    public HybridRetriever hybridRetriever(
            RetrievalHandler            handlerChain,          // ★ 체인 주입
            WebSearchRetriever            web,
            AnalyzeWebSearchRetriever     analyze,
            SelfAskWebSearchRetriever     selfAsk,
            LangChainRAGService           rag,
            QueryComplexityGate           gate,
            EmbeddingModel                embeddingModel,
            EmbeddingStore<TextSegment>   embeddingStore) {


        /* HybridRetriever 시그니처  ➜ (handlerChain, embeddingModel, embeddingStore) */
        return new HybridRetriever(handlerChain, embeddingModel, embeddingStore);
    }
}

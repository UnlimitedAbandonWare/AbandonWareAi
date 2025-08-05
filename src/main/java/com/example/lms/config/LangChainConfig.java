package com.example.lms.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;           // 🔹 인터페이스
import dev.langchain4j.model.openai.OpenAiChatModel;   // 구현체
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import com.example.lms.transform.QueryTransformer;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import com.example.lms.memory.PersistentChatMemory;   // ⭐ 추가
import lombok.RequiredArgsConstructor;                       // ⭐ 추가
import com.example.lms.repository.ChatMessageRepository;     // ⭐ 추가
import com.example.lms.repository.ChatSessionRepository;     // ⭐ 추가
import java.time.Duration;

@SuppressWarnings("removal")
@Configuration
@RequiredArgsConstructor            // ② Lombok으로 생성자 자동 생성
@ConditionalOnMissingBean(ChatMemoryProvider.class)   // 충돌 방지
public class LangChainConfig {


    private final ChatMessageRepository msgRepo;
    private final ChatSessionRepository sesRepo;

    /* ───── OpenAI / Pinecone 공통 ───── */
    @Value("${openai.api.key}")       private String openAiKey;
    @Value("${pinecone.api.key}")     private String pcKey;
    @Value("${pinecone.environment}") private String pcEnv;
    @Value("${pinecone.project.id}")  private String pcProjectId;
    @Value("${pinecone.index.name}")  private String pcIndex;
    @Value("${pinecone.embedding-model:text-embedding-3-small}")
    private String embeddingModelName;
    @Bean("persistentChatMemoryProvider")               // 실제 기능 반영
    @ConditionalOnMissingBean(ChatMemoryProvider.class) // 없을 때만 등록
    public ChatMemoryProvider persistentChatMemoryProvider() {
        // sessionId 파라미터 타입이 Object → 문자열로 변환
        return id -> new PersistentChatMemory(id.toString(), msgRepo, sesRepo);
    }




    /* ═════════ 1. LLM / 임베딩 ═════════ */
@Bean
public OpenAiChatModel chatModel(
        @Value("${lms.use-rag:true}") boolean useRagDefault) {  // 🔴 NEW
        return OpenAiChatModel.builder()
                .apiKey(openAiKey)
                .modelName("gpt-3.5-turbo")
                /* RAG OFF 모드일 때는 추측 최소화 */
                .temperature(useRagDefault ? 0.7 : 0.0)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(openAiKey)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }



    @Bean
    public QueryTransformer queryTransformer(OpenAiChatModel llm) {
        return new QueryTransformer(llm);
    }
    /* ① 기본 네임스페이스 */
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

    /* ② 게임 전용 Bean 삭제 → 단일 embeddingStore 사용 (@Primary) */
    /* ═════════ 2. 공통 유틸 ═════════ */
    @Bean
    public Analyzer koreanAnalyzer() {
        return new KoreanAnalyzer();
    }

    /* ═════════ 3. 웹‑리트리버들 ═════════ */
    @Bean
    public WebSearchRetriever webSearchRetriever(
            NaverSearchService svc,
            @Value("${search.web.top-k:3}") int topK) {

        return new WebSearchRetriever(svc, topK);
    }

    @Bean
    public SelfAskWebSearchRetriever selfAskWebSearchRetriever(
            ChatModel chatModel,
            NaverSearchService svc,
            @Value("${search.selfask.keyword-lines:3}") int keywordLines,
            @Value("${search.selfask.web-top-k:3}")     int webTopK,
            @Value("${search.selfask.overall-top-k:12}") int overallTopK) {

        return new SelfAskWebSearchRetriever(
                chatModel,
                svc,
                keywordLines,
                webTopK,
                overallTopK
        );
    }

    @Bean
    public AnalyzeWebSearchRetriever analyzeWebSearchRetriever(
            Analyzer koreanAnalyzer,
            NaverSearchService svc,
            @Value("${search.morph.max-tokens:5}") int maxTokens) {

        return new AnalyzeWebSearchRetriever(
                koreanAnalyzer, svc, maxTokens
        );
    }

    /* ═════════ 4. 벡터‑RAG 서비스 ═════════ */
    @Bean
    public LangChainRAGService langChainRAGService(
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> store,        // @Primary로 자동 선택
            MemoryReinforcementService memorySvc) {
        return new LangChainRAGService(
                chatModel,
                embeddingModel,
                store,
                memorySvc
        );
    }

    /* ═════════ 5. HybridRetriever ═════════ */
    @Bean
    public HybridRetriever hybridRetriever(
            SelfAskWebSearchRetriever selfAsk,
            AnalyzeWebSearchRetriever analyze,
            WebSearchRetriever web,
            LangChainRAGService rag,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore) {

        return new HybridRetriever(
                selfAsk, analyze, web, rag,
                embeddingModel, embeddingStore
        );
    }
}

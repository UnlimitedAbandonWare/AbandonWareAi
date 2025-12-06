package com.example.lms.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import com.example.lms.llm.NoopEmbeddingModel;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class LangChainRAGService {

    private static final Logger log = LoggerFactory.getLogger(LangChainRAGService.class);

    // 다른 서비스(ChatService, HybridRetriever 등)에서 참조하는 상수 정의
    public static final String META_SID = "sid";

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

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
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5) // 기본값 설정 (필요시 조정)
                .minScore(0.6)
                .build();
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
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(emb)
                    .maxResults(5)
                    .minScore(0.6) // 유사도 임계값
                    // .filter(MetadataFilterBuilder.metadataKey(META_SID).isEqualTo(sid)) // 메타데이터 필터링이 필요하면 주석 해제
                    .build();

            // 3. 검색 수행
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

            if (result.matches().isEmpty()) {
                log.debug("[RAG] Vector 0 matches sid={}", sid);
            }

            List<String> out = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : result.matches()) {
                out.add(match.embedded().text());
            }
            return out;

        } catch (Exception e) {
            log.warn("[RAG] retrieve error {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}

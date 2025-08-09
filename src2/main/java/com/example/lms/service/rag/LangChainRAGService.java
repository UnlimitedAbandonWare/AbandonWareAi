package com.example.lms.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ▶ 역할: “검색(Retrieve)”만 담당. LLM 호출은 하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class LangChainRAGService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Value("${rag.top-k:3}")   private int    topK;
    @Value("${rag.min-score:0.7}") private double minScore;

    /**
     * 사용자의 질의를 받아 관련 문서 텍스트 리스트를 돌려준다.
     */
    public List<String> retrieveContext(String query) {

        Embedding queryEmbedding = embeddingModel.embed(query).content();

        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(req);

        return result.matches().stream()
                .map(EmbeddingMatch::embedded)   // TextSegment
                .map(TextSegment::text)
                .collect(Collectors.toList());
    }
}

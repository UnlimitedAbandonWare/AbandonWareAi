package com.example.lms.service;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import com.example.lms.service.rag.CrossEncoderReranker; // ✅ 추가
import java.util.List;
import java.util.stream.Collectors;
import com.example.lms.service.rag.CrossEncoderReranker;

@Component
public class NoopCrossEncoderReranker implements CrossEncoderReranker {
    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (candidates == null) return List.of();
        if (topN <= 0) return candidates;
        return candidates.stream().limit(topN).collect(Collectors.toList());
    }
}

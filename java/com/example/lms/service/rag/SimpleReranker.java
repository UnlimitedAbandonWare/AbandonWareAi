package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Thin wrapper kept for backward compatibility.
 *
 * <p>기존 SimpleReranker 타입 의존성을 깨지 않으면서,
 * 실제 구현은 {@link LegacyLexicalReranker} 에서 관리한다.
 */
@Deprecated
@Component
public class SimpleReranker {

    private final LegacyLexicalReranker delegate;

    @Autowired
    public SimpleReranker(LegacyLexicalReranker delegate) {
        this.delegate = delegate;
    }

    public List<Content> rerank(String query, List<Content> candidates, int limit) {
        return delegate.rerank(query, candidates, limit);
    }
}


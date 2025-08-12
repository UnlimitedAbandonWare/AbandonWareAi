package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import java.util.List;

public interface CrossEncoderReranker {
    List<Content> rerank(String query, List<Content> candidates, int limit);
}

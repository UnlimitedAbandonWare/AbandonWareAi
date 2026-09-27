package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import java.util.List;

public interface LightWeightRanker {
    List<Content> rank(List<Content> candidates, String query, int limit);
}

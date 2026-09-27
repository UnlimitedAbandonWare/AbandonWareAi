package com.example.lms.service.rag.handler;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;

@FunctionalInterface
public interface RetrievalHandler {
    void handle(Query query, List<Content> accumulator);

    default RetrievalHandler linkWith(RetrievalHandler next) {
        return (query, accumulator) -> {
            this.handle(query, accumulator);
            if (next != null) {
                next.handle(query, accumulator);
            }
        };
    }
}

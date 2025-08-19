package com.example.lms.service.rag.handler;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KnowledgeGraphHandler retrieves entity relationships from the domain knowledge base
 * and formats them as RAG Content.  This handler is used as an additional
 * source in the retrieval chain, alongside Web and Vector sources.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeGraphHandler implements ContentRetriever {

    private final KnowledgeBaseService kbService;

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || kbService == null) {
            return List.of();
        }
        try {
            String text = query.text();
            String domain = kbService.inferDomain(text);
            Set<String> entities = kbService.findMentionedEntities(domain, text);
            if (entities == null || entities.isEmpty()) return List.of();
            List<Content> results = new ArrayList<>();
            for (String ent : entities) {
                try {
                    Map<String, Set<String>> rels = kbService.getAllRelationships(domain, ent);
                    if (rels == null || rels.isEmpty()) continue;
                    StringBuilder sb = new StringBuilder();
                    sb.append(ent).append(" 관계:\n");
                    for (Map.Entry<String, Set<String>> e : rels.entrySet()) {
                        sb.append("- ").append(e.getKey()).append(": ");
                        if (e.getValue() != null && !e.getValue().isEmpty()) {
                            sb.append(String.join(", ", e.getValue()));
                        }
                        sb.append("\n");
                    }
                    results.add(Content.from(sb.toString()));
                } catch (Exception ignore) {
                    // continue other entities
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("[KnowledgeGraphHandler] retrieve failed; returning empty list", e);
            return List.of();
        }
    }
}
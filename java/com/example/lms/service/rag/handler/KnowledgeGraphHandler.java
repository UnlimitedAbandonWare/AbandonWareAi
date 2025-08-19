package com.example.lms.service.rag.handler;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Retrieves structured relations from the knowledge base.  This handler is
 * read-only and never mutates the underlying graph.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeGraphHandler extends AbstractRetrievalHandler {

    private final KnowledgeBaseService kb;

    @Value("${retrieval.kg.domain:}")
    private String domain;

    @Override
    protected boolean doHandle(Query query, java.util.List<Content> acc) {
        if (query == null || query.text() == null) return true;
        try {
            Set<String> entities = kb.findMentionedEntities(domain, query.text());
            for (String name : entities) {
                Map<String, Set<String>> rels = kb.getAllRelationships(domain, name);
                if (rels.isEmpty()) continue;
                StringBuilder sb = new StringBuilder();
                sb.append(name).append(" relations:\n");
                rels.forEach((k,v) -> sb.append(k).append(':')
                        .append(String.join(",", v)).append("\n"));
                acc.add(Content.from(sb.toString()));
            }
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] read failed – skipping", e);
        }
        return true;
    }
}

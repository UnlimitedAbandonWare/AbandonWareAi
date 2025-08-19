package com.example.lms.service.rag.handler;

import com.example.lms.service.disambiguation.DisambiguationResult;
import com.example.lms.service.disambiguation.QueryDisambiguationService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Resolves ambiguous entity references in the query and rewrites the
 * underlying query text when confident.  Subsequent stages therefore
 * operate on a disambiguated query.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntityDisambiguationHandler extends AbstractRetrievalHandler {

    private final QueryDisambiguationService disambiguationService;

    @Override
    protected boolean doHandle(Query query, List<Content> acc) {
        if (query == null || query.text() == null) {
            return true;
        }
        try {
            DisambiguationResult r = disambiguationService.clarify(query.text(), List.of());
            if (r != null && r.isConfident()
                    && r.getRewrittenQuery() != null
                    && !r.getRewrittenQuery().isBlank()
                    && !r.getRewrittenQuery().equalsIgnoreCase(query.text())) {
                Field f = query.getClass().getDeclaredField("text");
                f.setAccessible(true);
                f.set(query, r.getRewrittenQuery());
            }
        } catch (Exception e) {
            log.warn("[EntityDisambiguation] failed – skipping", e);
        }
        return true;
    }
}

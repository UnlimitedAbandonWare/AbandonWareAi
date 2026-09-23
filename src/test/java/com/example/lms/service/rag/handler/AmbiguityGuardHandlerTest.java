package com.example.lms.service.rag.handler;

import com.example.lms.analysis.SenseDisambiguator;
import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AmbiguityGuardHandlerTest {

    @Test
    void disambiguatorFailureContinuesChainAndLeavesRedactedBreadcrumb() {
        SenseDisambiguator disambiguator = (query, peekTopK) -> {
            throw new IllegalStateException("raw sense secret");
        };
        AmbiguityGuardHandler handler = new AmbiguityGuardHandler(disambiguator, 0.15d);
        List<Content> accumulator = new ArrayList<>();

        TraceStore.clear();
        handler.handle(Query.from("ambiguous query"), accumulator);

        assertEquals(0, accumulator.size());
        assertEquals(true, TraceStore.get("rag.ambiguityGuard.suppressed.disambiguator"));
        assertEquals("IllegalStateException",
                TraceStore.get("rag.ambiguityGuard.disambiguator.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("raw sense secret"));
    }
}

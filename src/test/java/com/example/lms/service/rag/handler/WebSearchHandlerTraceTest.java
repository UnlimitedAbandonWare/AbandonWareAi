package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.search.terms.SelectedTerms;
import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchHandlerTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void retrieverFailureLeavesRedactedWebRetrievalTrace() {
        String secret = "sk-" + "webhandlersecret1234567890abcdef";
        String rawQuery = "web handler raw query " + secret;
        WebSearchHandler handler = new WebSearchHandler(new ThrowingRetriever(secret));
        List<Content> accumulator = new ArrayList<>();

        handler.handle(new Query(rawQuery), accumulator);

        assertTrue(accumulator.isEmpty());
        assertEquals("failed", TraceStore.get("retrieval.web.status"));
        assertEquals("web-search-handler-error", TraceStore.get("retrieval.web.disabledReason"));
        assertEquals("IllegalStateException", TraceStore.get("retrieval.web.failureClass"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.web.failSoft"));
        assertEquals(0, TraceStore.get("retrieval.web.addedCount"));
        assertEquals("failed", TraceStore.get("retrieval.dependency.web.status"));
        assertTrue(String.valueOf(TraceStore.get("retrieval.web.queryHash12")).matches("[0-9a-f]{12}"));
        assertEquals(rawQuery.length(), TraceStore.get("retrieval.web.queryLength"));
        assertTrue(TraceStore.get("retrieval.web.events") instanceof List<?>);

        String traceDump = String.valueOf(TraceStore.getAll());
        assertFalse(traceDump.contains(rawQuery));
        assertFalse(traceDump.contains(secret));
        assertFalse(traceDump.contains("api_key="));
    }

    @Test
    void effectiveQueryTraceUsesHashAndLengthOnly() {
        String rawQuery = "private effective query ownerToken=hidden-user-token";
        SelectedTerms selectedTerms = SelectedTerms.builder()
                .aliases(List.of("private alias token"))
                .negative(List.of("private negative token"))
                .domains(List.of("private.example.test"))
                .build();
        CapturingRetriever retriever = new CapturingRetriever();
        WebSearchHandler handler = new WebSearchHandler(retriever);

        TraceStore.put("selectedTerms", selectedTerms);
        handler.handle(new Query(rawQuery), new ArrayList<>());

        String effectiveQuery = retriever.lastQuery == null ? "" : retriever.lastQuery.text();
        assertTrue(effectiveQuery.contains(rawQuery));
        assertTrue(effectiveQuery.contains("private alias token"));
        assertTrue(effectiveQuery.contains("private.example.test"));
        assertEquals(null, TraceStore.get("web.effectiveQuery"));
        assertTrue(String.valueOf(TraceStore.get("web.effectiveQuery.hash12")).matches("[0-9a-f]{12}"));
        assertEquals(effectiveQuery.length(), TraceStore.get("web.effectiveQuery.len"));

        String traceDump = String.valueOf(TraceStore.getAll());
        assertFalse(traceDump.contains(rawQuery), traceDump);
        assertFalse(traceDump.contains("private alias token"), traceDump);
        assertFalse(traceDump.contains("private negative token"), traceDump);
        assertFalse(traceDump.contains("private.example.test"), traceDump);
        assertFalse(traceDump.contains("ownerToken=hidden-user-token"), traceDump);
    }

    private static final class ThrowingRetriever extends WebSearchRetriever {
        private final String secret;

        private ThrowingRetriever(String secret) {
            super(null, null, null, null, null, null, null);
            this.secret = secret;
        }

        @Override
        public List<Content> retrieve(Query query) {
            throw new IllegalStateException("provider failed api_key=" + secret);
        }
    }

    private static final class CapturingRetriever extends WebSearchRetriever {
        private Query lastQuery;

        private CapturingRetriever() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public List<Content> retrieve(Query query) {
            lastQuery = query;
            return List.of();
        }
    }
}

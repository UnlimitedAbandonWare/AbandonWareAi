package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAskHandlerTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void retrieverFailureLeavesRedactedSelfAskTraceAndContinues() {
        String fakeTokenValue = "sk-" + "selfasktoken1234567890abcdef";
        String rawQuery = "self ask raw query ownerToken=hidden " + fakeTokenValue;
        SelfAskHandler handler = new SelfAskHandler(new ThrowingRetriever(fakeTokenValue), null);
        List<Content> accumulator = new ArrayList<>();

        handler.handle(new Query(rawQuery), accumulator);

        assertTrue(accumulator.isEmpty());
        assertEquals("failed", TraceStore.get("retrieval.selfAsk.status"));
        assertEquals("selfask-handler-error", TraceStore.get("retrieval.selfAsk.disabledReason"));
        assertEquals("IllegalStateException", TraceStore.get("retrieval.selfAsk.failureClass"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.selfAsk.failSoft"));
        assertEquals(0, TraceStore.get("retrieval.selfAsk.addedCount"));
        assertEquals("failed", TraceStore.get("retrieval.dependency.selfAsk.status"));
        assertTrue(String.valueOf(TraceStore.get("retrieval.selfAsk.queryHash12")).matches("[0-9a-f]{12}"));
        assertEquals(rawQuery.length(), TraceStore.get("retrieval.selfAsk.queryLength"));
        assertTrue(TraceStore.get("retrieval.selfAsk.events") instanceof List<?>);

        String traceDump = String.valueOf(TraceStore.getAll());
        assertFalse(traceDump.contains(rawQuery), traceDump);
        assertFalse(traceDump.contains(fakeTokenValue), traceDump);
        assertFalse(traceDump.contains("ownerToken=hidden"), traceDump);
        assertFalse(traceDump.contains("api_key="), traceDump);
    }

    private static final class ThrowingRetriever extends SelfAskWebSearchRetriever {
        private final String tokenValue;

        private ThrowingRetriever(String tokenValue) {
            super(new DisabledProvider(), null, null, null);
            this.tokenValue = tokenValue;
        }

        @Override
        public List<Content> retrieve(Query query) {
            throw new IllegalStateException("self ask failed api_key=" + tokenValue);
        }
    }

    private static final class DisabledProvider implements WebSearchProvider {
        @Override
        public List<String> search(String query, int topK) {
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public String getName() {
            return "disabled";
        }
    }
}

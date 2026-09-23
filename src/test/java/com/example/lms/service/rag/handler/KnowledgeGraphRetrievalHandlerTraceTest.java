package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeGraphRetrievalHandlerTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void recordsRedactedFailureClassWhenKgDelegateFailsSoft() {
        KnowledgeGraphRetrievalHandler handler =
                new KnowledgeGraphRetrievalHandler(new ThrowingKnowledgeGraphHandler());
        List<Content> accumulator = new ArrayList<>();

        handler.handle(new Query("secret raw kg query"), accumulator);

        assertTrue(accumulator.isEmpty());
        assertEquals("failed", TraceStore.get("retrieval.kg.fixedChain.status"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.fixedChain.enabled"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.fixedChain.failSoft"));
        assertEquals("delegate_exception", TraceStore.get("retrieval.kg.fixedChain.disabledReason"));
        assertEquals("IllegalStateException", TraceStore.get("retrieval.kg.fixedChain.failureClass"));
        assertTrue(String.valueOf(TraceStore.get("retrieval.kg.fixedChain.queryHash12")).matches("[0-9a-f]{12}"));
        assertTrue(TraceStore.get("retrieval.kg.fixedChain.events") instanceof List<?>);
        assertFalse(String.valueOf(TraceStore.getAll()).contains("secret raw kg query"));
    }

    @Test
    void recordsCancelledFailureClassWhenKgDelegateIsCancelled() {
        KnowledgeGraphRetrievalHandler handler =
                new KnowledgeGraphRetrievalHandler(new CancellingKnowledgeGraphHandler());
        List<Content> accumulator = new ArrayList<>();

        handler.handle(new Query("secret raw kg cancellation query"), accumulator);

        assertTrue(accumulator.isEmpty());
        assertEquals("failed", TraceStore.get("retrieval.kg.fixedChain.status"));
        assertEquals("delegate_exception", TraceStore.get("retrieval.kg.fixedChain.disabledReason"));
        assertEquals("cancelled", TraceStore.get("retrieval.kg.fixedChain.failureClass"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("secret raw kg cancellation query"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    private static final class ThrowingKnowledgeGraphHandler extends KnowledgeGraphHandler {
        private ThrowingKnowledgeGraphHandler() {
            super((KnowledgeBaseService) null);
        }

        @Override
        public List<Content> retrieve(Query query) {
            throw new IllegalStateException("kg backend failed with secret raw kg query");
        }
    }

    private static final class CancellingKnowledgeGraphHandler extends KnowledgeGraphHandler {
        private CancellingKnowledgeGraphHandler() {
            super((KnowledgeBaseService) null);
        }

        @Override
        public List<Content> retrieve(Query query) {
            throw new CancellationException("cancelled ownerToken fake-token");
        }
    }
}

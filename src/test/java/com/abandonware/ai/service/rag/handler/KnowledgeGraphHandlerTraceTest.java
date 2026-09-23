package com.abandonware.ai.service.rag.handler;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeGraphHandlerTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        Thread.interrupted();
    }

    @Test
    void interruptedAcquireRecordsTraceAndPreservesInterrupt() {
        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(1, 100, 5);

        Thread.currentThread().interrupt();

        assertTrue(handler.lookup("rag", 5).isEmpty());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(Boolean.TRUE, TraceStore.get("kg.abandonware.suppressed"));
        assertEquals("tryAcquire.interrupted", TraceStore.get("kg.abandonware.suppressed.stage"));
        assertEquals("cancelled", TraceStore.get("kg.abandonware.suppressed.errorClass"));
    }
}

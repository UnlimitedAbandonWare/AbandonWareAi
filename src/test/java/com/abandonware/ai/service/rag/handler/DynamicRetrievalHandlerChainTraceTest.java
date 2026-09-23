package com.abandonware.ai.service.rag.handler;

import com.abandonware.ai.agent.service.rag.bm25.Bm25LocalRetriever;
import com.example.lms.search.TraceStore;
import com.example.lms.strategy.RetrievalOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicRetrievalHandlerChainTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void safeRetrieverFailureRecordsStageWithoutRawQuery() {
        RetrievalOrderService orderService = mock(RetrievalOrderService.class);
        when(orderService.decideOrder("private dynamic query"))
                .thenReturn(List.of(RetrievalOrderService.Source.VECTOR));
        Bm25LocalRetriever bm25 = mock(Bm25LocalRetriever.class);
        when(bm25.retrieve("private dynamic query", 6))
                .thenThrow(new IllegalStateException("raw private dynamic query"));
        DynamicRetrievalHandlerChain chain = new DynamicRetrievalHandlerChain(orderService);
        ReflectionTestUtils.setField(chain, "bm25", bm25);
        ReflectionTestUtils.setField(chain, "bm25K", 6);

        assertTrue(chain.retrieve("private dynamic query").isEmpty());

        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.dynamic.safe.suppressed"));
        assertEquals("vector", TraceStore.get("retrieval.dynamic.safe.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("retrieval.dynamic.safe.suppressed.errorClass"));
        String snapshot = TraceStore.getAll().toString();
        assertFalse(snapshot.contains("private dynamic query"));
        assertFalse(snapshot.contains("raw private"));
    }
}

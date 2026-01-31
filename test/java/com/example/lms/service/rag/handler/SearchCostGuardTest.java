package com.example.lms.service.rag.handler;

import dev.langchain4j.rag.query.DefaultQuery;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SearchCostGuardHandler}.  These tests verify that
 * the guard emits a relief hint when the estimated token count meets or
 * exceeds the configured threshold and remains silent otherwise.  A
 * simple {@link AtomicBoolean} flag is used to capture the invocation
 * of the onRelief callback.
 */
public class SearchCostGuardTest {

    @Test
    public void guardShouldEmitReliefHintWhenThresholdExceeded() {
        AtomicBoolean triggered = new AtomicBoolean(false);
        SearchCostGuardHandler guard = new SearchCostGuardHandler(
                s -> 15000,
                12000,
                msg -> triggered.set(true)
        );
        Query q = new DefaultQuery("dummy query", null);
        guard.handle(q, new ArrayList<>());
        assertTrue(triggered.get(), "Guard should trigger relief hint when threshold is exceeded");
    }

    @Test
    public void guardShouldNotEmitWhenBelowThreshold() {
        AtomicBoolean triggered = new AtomicBoolean(false);
        SearchCostGuardHandler guard = new SearchCostGuardHandler(
                s -> 1000,
                12000,
                msg -> triggered.set(true)
        );
        Query q = new DefaultQuery("dummy query", null);
        guard.handle(q, new ArrayList<>());
        assertFalse(triggered.get(), "Guard should not trigger when below threshold");
    }
}
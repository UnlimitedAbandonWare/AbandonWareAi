package com.abandonware.ai.addons.flow;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowJoinerRedactionTest {

    @Test
    void nonFiniteStageProbabilityFailsSafeToFallback() {
        FlowJoiner joiner = new FlowJoiner();
        joiner.mark(FlowStage.RETRIEVE, Double.NaN);

        FlowHealthScore score = joiner.score();

        assertTrue(score.below(0.99d));
        assertTrue(new FlowHealthScore(1, 1, 1, 1, 1, Double.NaN).below(0.01d));
        assertEquals("fallback", joiner.withFallback(() -> "primary", () -> "fallback", 0.99d));
    }

    @Test
    void fallbackFailureLogDoesNotExposeRawExceptionMessage() {
        FlowJoiner joiner = new FlowJoiner();
        Logger logger = Logger.getLogger(FlowJoiner.class.getName());
        StringBuilder captured = new StringBuilder();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.append(record.getMessage()).append('\n');
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() throws SecurityException {
            }
        };
        Level oldLevel = logger.getLevel();
        boolean oldUseParentHandlers = logger.getUseParentHandlers();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        try {
            String value = joiner.withFallback(
                    () -> {
                        throw new IllegalStateException(
                                "flow failed ownerToken=raw-flow-token at C:\\Users\\nninn\\secret\\flow.txt");
                    },
                    () -> "fallback",
                    0.5d);
            assertEquals("fallback", value);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(oldLevel);
            logger.setUseParentHandlers(oldUseParentHandlers);
        }

        String log = captured.toString();
        assertFalse(log.contains("raw-flow-token"));
        assertFalse(log.contains("C:\\Users\\nninn"));
        assertFalse(log.contains("ownerToken"));
        assertFalse(log.contains("flow failed"));
        assertTrue(log.contains("errorHash="));
        assertTrue(log.contains("errorLength="));
        assertTrue(log.contains("errorType=IllegalStateException"));
    }

    @Test
    void fallbackFailureLeavesTraceBreadcrumbWithoutRawExceptionMessage() {
        TraceStore.clear();
        FlowJoiner joiner = new FlowJoiner();

        String value = joiner.withFallback(
                () -> {
                    throw new IllegalStateException("flow failed ownerToken=raw-flow-token");
                },
                () -> "fallback",
                0.5d);

        assertEquals("fallback", value);
        assertEquals(Boolean.TRUE, TraceStore.get("addons.flow.primary.suppressed"));
        assertEquals("primary_failed", TraceStore.get("addons.flow.primary.reason"));
        assertEquals("IllegalStateException", TraceStore.get("addons.flow.primary.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-flow-token"));
    }
}

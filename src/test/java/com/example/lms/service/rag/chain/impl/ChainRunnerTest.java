package com.example.lms.service.rag.chain.impl;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareBreakerProperties;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.chain.AttachmentContextHandler;
import com.example.lms.service.rag.chain.Chain;
import com.example.lms.service.rag.chain.ChainContext;
import com.example.lms.service.rag.chain.ChainOutcome;
import com.example.lms.service.rag.chain.ImagePromptGroundingHandler;
import com.example.lms.service.rag.chain.LocationInterceptHandler;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainRunnerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void handlerExceptionReturnsPassAndEmitsRedactedDiagnostic() {
        DebugEventStore store = enabledDebugEventStore();
        FaultMaskingLayerMonitor faultMaskingLayerMonitor = new FaultMaskingLayerMonitor();
        NightmareBreaker nightmareBreaker = new NightmareBreaker(new NightmareBreakerProperties());
        ChainRunner runner = new ChainRunner(
                new ThrowingLocationInterceptHandler(),
                new AttachmentContextHandler(null),
                new ImagePromptGroundingHandler(null),
                providerFor(DebugEventStore.class, "debugEventStore", store),
                providerFor(FaultMaskingLayerMonitor.class, "faultMaskingLayerMonitor", faultMaskingLayerMonitor),
                providerFor(NightmareBreaker.class, "nightmareBreaker", nightmareBreaker));

        ChainOutcome outcome = runner.run(
                "session-very-secret-123",
                "user-42",
                "very-secret-user-query should not appear in diagnostics",
                null);

        assertEquals(ChainOutcome.PASS, outcome);

        List<DebugEvent> events = store.list(5);
        DebugEvent event = events.stream()
                .filter(e -> SafeRedactor.hashValue("chainrunner.failsoft.IllegalStateException")
                        .equals(e.fingerprint()))
                .findFirst()
                .orElseThrow();

        assertEquals(DebugProbeType.FAULT_MASK, event.probe());
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("ChainRunner.run", event.where());
        assertEquals("PASS", event.data().get("outcome"));
        assertEquals("IllegalStateException", event.data().get("exceptionType"));
        assertEquals(NightmareKeys.RAG_CHAIN_HANDLER, event.data().get("nightmareKey"));
        assertEquals(NightmareKeys.RAG_CHAIN_HANDLER, event.data().get("faultMaskStage"));
        assertEquals(Boolean.TRUE, event.data().get("hasSessionId"));
        assertEquals(Boolean.TRUE, event.data().get("hasUserId"));

        String diagnostic = event.toString();
        assertFalse(diagnostic.contains("very-secret-user-query"));
        assertFalse(diagnostic.contains("session-very-secret-123"));
        assertTrue(String.valueOf(event.data().get("sessionHash")).matches("[0-9a-f]{12}"));

        assertEquals(NightmareKeys.RAG_CHAIN_HANDLER, TraceStore.get("faultmask.stage"));
        assertEquals(NightmareKeys.RAG_CHAIN_HANDLER, TraceStore.get("nightmare.silent.lastKey"));
        String traceDump = String.valueOf(TraceStore.getAll());
        assertTrue(traceDump.contains("handler_exception:IllegalStateException"));
        assertFalse(traceDump.contains("very-secret-user-query"));
        assertFalse(traceDump.contains("session-very-secret-123"));
    }

    private static DebugEventStore enabledDebugEventStore() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        return store;
    }

    private static <T> ObjectProvider<T> providerFor(Class<T> type, String name, T bean) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton(name, bean);
        return factory.getBeanProvider(type);
    }

    private static final class ThrowingLocationInterceptHandler extends LocationInterceptHandler {
        private ThrowingLocationInterceptHandler() {
            super(null);
        }

        @Override
        public ChainOutcome handle(ChainContext ctx, Chain next) {
            throw new IllegalStateException("boom");
        }
    }
}

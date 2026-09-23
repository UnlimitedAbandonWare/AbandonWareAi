package com.example.lms.service.soak.metrics;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SoakWebKpiMinuteSummaryLoggerDebugEventTest {

    @Test
    void minuteSummaryEmitsRedactedWebSearchDebugEventWhenOutCountStarves() {
        CapturingDebugEventStore store = new CapturingDebugEventStore();
        SoakWebKpiMinuteSummaryLogger logger = new SoakWebKpiMinuteSummaryLogger(provider(store));

        logger.record(0, true, false, false, false, false, false);
        logger.emitMinuteSummary();

        assertFalse(store.events.isEmpty());
        CapturedEvent event = store.events.get(0);
        assertEquals(DebugProbeType.WEB_SEARCH, event.probe);
        assertEquals(DebugEventLevel.WARN, event.level);
        assertEquals("zero-result-after-filter", event.data.get("failureClass"));
        assertEquals("web.search", event.data.get("layer"));
        assertEquals(1.0d, (Double) event.data.get("outCount.zeroRatio"));
    }

    @Test
    void minuteSummaryClassifiesAwaitInterruptedAsCancelled() {
        CapturingDebugEventStore store = new CapturingDebugEventStore();
        SoakWebKpiMinuteSummaryLogger logger = new SoakWebKpiMinuteSummaryLogger(provider(store));

        logger.record(3, false, false, false, false, false, true);
        logger.emitMinuteSummary();

        CapturedEvent event = store.events.get(0);
        assertEquals("cancelled", event.data.get("failureClass"));
        assertEquals("await-interrupted", event.data.get("reason"));
        assertEquals(1.0d, (Double) event.data.get("await.interrupted.ratio"));
    }

    @Test
    void minuteSummaryKeepsLadderRescueSignalsWhenOutCountIsNonzero() {
        CapturingDebugEventStore store = new CapturingDebugEventStore();
        SoakWebKpiMinuteSummaryLogger logger = new SoakWebKpiMinuteSummaryLogger(provider(store));

        logger.record(4,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                2,
                7,
                true);
        logger.emitMinuteSummary();

        CapturedEvent event = store.events.get(0);
        assertEquals("soak-web-kpi-anomaly", event.data.get("failureClass"));
        assertEquals("soak-kpi-anomaly", event.data.get("reason"));
        assertEquals(1.0d, (Double) event.data.get("poolSafeEmpty.ratio"));
        assertEquals(1.0d, (Double) event.data.get("rescueMerge.used.ratio"));
        assertEquals(1.0d, (Double) event.data.get("vectorFallback.used.ratio"));
        assertEquals(2L, event.data.get("cacheOnly.merged.count.sum"));
        assertEquals(7L, event.data.get("tracePool.size.max"));
        assertEquals(1.0d, (Double) event.data.get("starvationFallback.trigger.ratio"));
    }

    private record CapturedEvent(DebugProbeType probe,
                                 DebugEventLevel level,
                                 String fingerprint,
                                 String message,
                                 String where,
                                 Map<String, Object> data) {
    }

    private static final class CapturingDebugEventStore extends DebugEventStore {
        private final List<CapturedEvent> events = new ArrayList<>();

        @Override
        public void emit(DebugProbeType probe,
                         DebugEventLevel level,
                         String fingerprint,
                         String message,
                         String where,
                         Map<String, Object> data,
                         Throwable error) {
            events.add(new CapturedEvent(probe, level, fingerprint, message, where, data));
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}

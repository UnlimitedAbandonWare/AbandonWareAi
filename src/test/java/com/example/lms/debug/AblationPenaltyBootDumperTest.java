package com.example.lms.debug;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AblationPenaltyBootDumperTest {

    @AfterEach
    void clearTraceStore() throws Exception {
        TraceStore.clear();
        effectivePenalties().set(Map.of());
    }

    @Test
    void publishesEffectiveAblationPenaltiesToTraceStore() {
        TraceStore.clear();
        MockEnvironment env = new MockEnvironment()
                .withProperty("uaw.ablation.penalty.default", "0.20")
                .withProperty("uaw.ablation.penalty.websearch", "0.40")
                .withProperty("uaw.ablation.penalty.websearch.starvation", "0.30");

        new AblationPenaltyBootDumper(env).onApplicationEvent(null);

        Map<?, ?> penalties = assertInstanceOf(Map.class, TraceStore.get("ablation.penalties"));
        assertEquals(0.20d, ((Number) penalties.get("default")).doubleValue(), 0.0001d);
        assertEquals(0.40d, ((Number) penalties.get("websearch.base")).doubleValue(), 0.0001d);
        assertEquals(0.30d, ((Number) penalties.get("websearch.starvation")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) TraceStore.get("ablation.score.current")).doubleValue(), 0.0001d);
    }

    @Test
    void seedCurrentTracePublishesEmptyBaselineWhenBootListenerHasNotRun() throws Exception {
        effectivePenalties().set(Map.of());

        AblationPenaltyBootDumper.seedCurrentTrace();

        assertNotNull(TraceStore.get("ablation.penalties"));
        Map<?, ?> penalties = assertInstanceOf(Map.class, TraceStore.get("ablation.penalties"));
        assertEquals(0, penalties.size());
        assertEquals(1.0d, ((Number) TraceStore.get("ablation.score.current")).doubleValue(), 0.0001d);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Map<String, Object>> effectivePenalties() throws Exception {
        var field = AblationPenaltyBootDumper.class.getDeclaredField("EFFECTIVE_PENALTIES");
        field.setAccessible(true);
        return (AtomicReference<Map<String, Object>>) field.get(null);
    }
}

package com.abandonware.ai.agent.orchestrator.nodes;

import com.abandonware.ai.agent.orchestrator.recovery.Verdict;
import com.example.lms.search.TraceStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CriticNodeTraceTest {
    @BeforeEach
    void clearTraceStoreBeforeTest() {
        TraceStore.clear();
    }

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void publishesMoeCriticTraceWithoutRawPolicyText() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("policy.denied", true);
        state.put("rag.retrieve", List.of("doc1", "doc2", "doc3"));
        state.put("citations", List.of("c1", "c2", "c3"));

        Verdict verdict = (Verdict) new CriticNode().run(state).get("verdict");

        assertEquals(Verdict.Decision.ABORT, verdict.decision());
        assertEquals(1, TraceStore.get("moe.criticAttempts"));
        assertEquals(Boolean.TRUE, TraceStore.get("moe.criticExhausted"));
        assertEquals("policy_denied", TraceStore.get("moe.criticLastReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("critic.traceContextPresent"));
        assertEquals(Boolean.TRUE, TraceStore.get("critic.exhausted"));
        assertEquals("policy_denied", TraceStore.get("critic.exhaustedReason"));
    }
}

package com.abandonware.ai.agent.orchestrator.recovery;

import com.abandonware.ai.agent.fallback.NovaFallbackCoordinator;
import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.orchestrator.nodes.CriticNode;
import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.SseEventPublisher;
import com.example.lms.trace.TraceContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryFlowIntegrationTest {
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void quotedYamlValuesDriveRecoveryPolicy() {
        RecoveryPolicy policy = RecoveryPolicy.fromYaml("""
                recovery:
                  max-rounds: "1"
                  min-citations: "2"
                  budget-soft-ms: "250"
                map:
                  DATA: "FALLBACK"
                  BUDGET: 'DEGRADE'
                degrade-map:
                  brave.v1: "safe_autorun.v1"
                """);

        assertEquals(1, policy.maxRounds());
        assertEquals(2, policy.minCitations());
        assertEquals(250, policy.budgetSoftMs());
        assertEquals(RecoveryAction.FALLBACK, policy.resolve(FailureClass.DATA));
        assertEquals(RecoveryAction.DEGRADE, policy.resolve(FailureClass.BUDGET));
        assertEquals("safe_autorun.v1", policy.degrade("brave.v1"));
    }

    @Test
    void invalidPolicyNumbersFallbackWithRedactedBreadcrumb() {
        RecoveryPolicy policy = RecoveryPolicy.fromYaml("""
                recovery:
                  max-rounds: "private max rounds"
                  min-citations: "private citations"
                  budget-soft-ms: "private budget"
                """);

        assertEquals(2, policy.maxRounds());
        assertEquals(3, policy.minCitations());
        assertEquals(500, policy.budgetSoftMs());
        assertEquals(Boolean.TRUE, TraceStore.get("agent.recovery.policy.suppressed"));
        assertEquals("positiveInt", TraceStore.get("agent.recovery.policy.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("agent.recovery.policy.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private max rounds"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private citations"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private budget"));
    }

    @Test
    void recoveryRoundPublishesSingleRedactedSseEvent() {
        RecoveryPolicy policy = RecoveryPolicy.fromYaml("""
                recovery:
                  max-rounds: 2
                  min-citations: 3
                  budget-soft-ms: 500
                map:
                  DATA: FALLBACK
                degrade-map:
                  brave.v1: safe_autorun.v1
                """);
        RecordingSseEventPublisher sse = new RecordingSseEventPublisher();
        DefaultRecoveryExecutor executor =
                new DefaultRecoveryExecutor(policy, new NovaFallbackCoordinator(), sse);
        Orchestrator orchestrator = new Orchestrator(executor, new CriticNode(policy), policy);

        try (TraceContext ignored = TraceContext.attach("recovery-test", "sse")) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("query", "raw secret recovery query");
            state.put("api-key", "sk-local-secret-value");
            state.put("ownerToken", "owner-token-secret-value");
            state.put("rag.retrieve", List.of("doc1"));
            state.put("citations", List.of("c1"));

            Map<String, Object> out = orchestrator.execute("brave.v1", state, null);

            assertEquals("FALLBACK", out.get("recovery.action"));
            assertEquals(1, out.get("recovery.round"));
            assertEquals(1, sse.events.size());
            RecordedEvent event = sse.events.get(0);
            assertEquals("agent.recovery.fallback", event.type());
            assertTrue(event.payload() instanceof Map<?, ?>);

            Map<?, ?> payload = (Map<?, ?>) event.payload();
            assertEquals(1, payload.get("round"));
            assertEquals("FALLBACK", payload.get("action"));
            assertEquals("DATA", payload.get("failureClass"));

            String rendered = String.valueOf(payload);
            assertFalse(rendered.contains("raw secret recovery query"));
            assertFalse(rendered.contains("sk-local-secret-value"));
            assertFalse(rendered.contains("owner-token-secret-value"));
        }
    }

    @Test
    void recoveryEventMasksVerdictReason() {
        RecordingSseEventPublisher sse = new RecordingSseEventPublisher();
        DefaultRecoveryExecutor executor = new DefaultRecoveryExecutor(
                RecoveryPolicy.load(), new NovaFallbackCoordinator(), sse);
        Verdict verdict = new Verdict(
                Verdict.Decision.RECOVER,
                FailureClass.DATA,
                0.5,
                "provider failed with " + "sk-" + "123456789012345678901234567890 and raw query",
                Map.of());

        executor.apply(RecoveryAction.BACKOFF, verdict, Map.of(), null);

        assertEquals(1, sse.events.size());
        Map<?, ?> payload = (Map<?, ?>) sse.events.get(0).payload();
        String rendered = String.valueOf(payload);
        assertTrue(String.valueOf(payload.get("reason")).startsWith("hash:"));
        assertFalse(rendered.contains("sk-" + "123456789012345678901234567890"));
        assertFalse(rendered.contains("raw query"));
        assertFalse(rendered.contains("\n"));
    }

    @Test
    void fallbackCoordinatorMasksVerdictReasonInUserMessage() {
        NovaFallbackCoordinator coordinator = new NovaFallbackCoordinator();
        Verdict verdict = new Verdict(
                Verdict.Decision.RECOVER,
                FailureClass.DATA,
                0.5,
                "provider failed with " + "sk-" + "123456789012345678901234567890 and raw query",
                Map.of());

        String message = coordinator.handle("query", List.of(), verdict);

        assertTrue(message.contains("reason: hash:"), message);
        assertFalse(message.contains("sk-" + "123456789012345678901234567890"));
        assertFalse(message.contains("raw query"));
    }

    @Test
    void ssePublishFailureLeavesRedactedBreadcrumb() {
        DefaultRecoveryExecutor executor = new DefaultRecoveryExecutor(
                RecoveryPolicy.load(),
                new NovaFallbackCoordinator(),
                (type, payload) -> {
                    throw new IllegalStateException("private sse sink failure");
                });
        Verdict verdict = new Verdict(
                Verdict.Decision.RECOVER,
                FailureClass.DATA,
                0.5,
                "private recovery reason",
                Map.of());

        Map<String, Object> patch = executor.apply(RecoveryAction.BACKOFF, verdict, Map.of(), null);

        assertEquals("BACKOFF", patch.get("recovery.action"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.recovery.sse.suppressed"));
        assertEquals("sse.emit", TraceStore.get("agent.recovery.sse.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("agent.recovery.sse.suppressed.errorType"));
        assertEquals("agent.recovery.backoff", TraceStore.get("agent.recovery.sse.suppressed.eventType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private sse sink failure"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private recovery reason"));
    }

    @Test
    void numericCoercionFallbackLeavesRedactedBreadcrumb() {
        DefaultRecoveryExecutor executor = new DefaultRecoveryExecutor(
                RecoveryPolicy.load(),
                new NovaFallbackCoordinator(),
                null);
        Verdict verdict = new Verdict(
                Verdict.Decision.RECOVER,
                FailureClass.DATA,
                0.5,
                "safe reason",
                Map.of());

        Map<String, Object> backoff = executor.apply(
                RecoveryAction.BACKOFF,
                verdict,
                Map.of("retry.initialMs", "private retry value"),
                null);
        assertEquals(0L, backoff.get("recovery.backoff.ms"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.recovery.coerce.suppressed"));
        assertEquals("long", TraceStore.get("agent.recovery.coerce.suppressed.stage"));

        executor.apply(
                RecoveryAction.FALLBACK,
                verdict,
                Map.of(
                        "query", "private query",
                        "results", List.of(Map.of(
                                "id", "doc-1",
                                "title", "title",
                                "snippet", "snippet",
                                "source", "source",
                                "score", "private score value",
                                "rank", 1))),
                null);
        assertEquals("double", TraceStore.get("agent.recovery.coerce.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("agent.recovery.coerce.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private retry value"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private score value"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private query"));
    }

    @Test
    void nonFiniteNumericCoercionLeavesRedactedBreadcrumb() {
        DefaultRecoveryExecutor executor = new DefaultRecoveryExecutor(
                RecoveryPolicy.load(),
                new NovaFallbackCoordinator(),
                null);
        Verdict verdict = new Verdict(
                Verdict.Decision.RECOVER,
                FailureClass.DATA,
                0.5,
                "safe reason",
                Map.of());

        executor.apply(
                RecoveryAction.FALLBACK,
                verdict,
                Map.of(
                        "query", "private query",
                        "results", List.of(Map.of(
                                "id", "doc-1",
                                "title", "title",
                                "snippet", "snippet",
                                "source", "source",
                                "score", Double.POSITIVE_INFINITY,
                                "rank", 1))),
                null);

        assertEquals(Boolean.TRUE, TraceStore.get("agent.recovery.coerce.suppressed"));
        assertEquals("double", TraceStore.get("agent.recovery.coerce.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("agent.recovery.coerce.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("Infinity"));
    }

    private static final class RecordingSseEventPublisher implements SseEventPublisher {
        private final List<RecordedEvent> events = new ArrayList<>();

        @Override
        public void emit(String type, Object payload) {
            events.add(new RecordedEvent(type, payload));
        }
    }

    private record RecordedEvent(String type, Object payload) {}
}

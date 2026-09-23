package com.abandonware.ai.agent.orchestrator.recovery;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.orchestrator.nodes.CriticNode;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecoveryRoundTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void acceptPathDoesNotCreateRecoveryRound() {
        try (TraceContext trace = TraceContext.attach("recovery-test", "accept")) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("rag.retrieve", List.of("doc1", "doc2", "doc3"));
            state.put("citations", List.of("c1", "c2", "c3"));

            Verdict verdict = (Verdict) new CriticNode().run(state).get("verdict");

            assertEquals(Verdict.Decision.ACCEPT, verdict.decision());
            assertNull(trace.getFlag("recovery.rounds"));
        }
    }

    @Test
    void criticNodePublishesMoeCriticTrace() {
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

    @Test
    void dataFailureFallsBackAndRecordsRound() {
        try (TraceContext trace = TraceContext.attach("recovery-test", "data")) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("rag.retrieve", List.of("doc1"));
            state.put("citations", List.of("c1"));

            Map<String, Object> out = new Orchestrator().execute("brave.v1", state, null);

            assertEquals(1, out.get("recovery.round"));
            assertEquals("FALLBACK", out.get("recovery.action"));
            assertNotNull(out.get("fallback.message"));
            assertRoundCount(trace, 1);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoveryDecisionStoresRecallableDurableBreadcrumbMemory() throws Exception {
        Path memory = tempDir.resolve("failure-pattern-memory.jsonl");
        FailurePatternMemoryService service = new FailurePatternMemoryService(
                new ObjectMapper(), new ToolManifestCatalog(), tempDir, memory);
        ObjectProvider<FailurePatternMemoryService> memoryProvider = mock(ObjectProvider.class);
        when(memoryProvider.getIfAvailable()).thenReturn(service);
        Orchestrator orchestrator = new Orchestrator();
        ReflectionTestUtils.setField(orchestrator, "failurePatternMemory", memoryProvider);

        try (TraceContext ignored = TraceContext.attach("recovery-test", "durable-memory")) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("query", "private customer query sk-" + "redactioncontract1234567890");
            state.put("rag.retrieve", List.of("doc1"));
            state.put("citations", List.of("c1"));

            orchestrator.execute("brave.v1", state, null);
        }

        String line = Files.readString(memory);
        assertTrue(line.contains("\"kind\":\"agent_judgment_breadcrumb\""));
        assertTrue(line.contains("\"source\":\"agent_breadcrumb\""));
        assertTrue(line.contains("\"failureClass\":\"DATA\""));
        assertTrue(line.contains("\"hotspot\":\"brave.v1\""));
        assertFalse(line.contains("private customer query"));
        assertFalse(line.contains("sk-redactioncontract"));
        assertFalse(line.contains("recovery-test"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.breadcrumb.memory.stored"));
        assertEquals("agent_judgment_breadcrumb", TraceStore.get("agent.breadcrumb.memory.kind"));

        Map<String, Object> recalled = service.recall(Map.of(
                "kind", "agent_judgment_breadcrumb",
                "source", "agent_breadcrumb",
                "failureClass", "DATA",
                "hotspot", "brave.v1"));
        assertEquals(1, recalled.get("matchCount"));
        List<Map<String, Object>> matches = (List<Map<String, Object>>) recalled.get("matches");
        assertEquals("FALLBACK", matches.get(0).get("patchAction"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoveryDecisionAddsPriorMemoryRecallToTimelineBreadcrumb() {
        FailurePatternMemoryService service = new FailurePatternMemoryService(
                new ObjectMapper(), new ToolManifestCatalog(), tempDir, tempDir.resolve("failure-pattern-memory.jsonl"));
        service.record(Map.of(
                "kind", "agent_judgment_breadcrumb",
                "source", "agent_breadcrumb",
                "failureClass", "DATA",
                "hotspot", "brave.v1",
                "intent", "agent_judgment_breadcrumb|brave.v1|DATA",
                "evidence", "prior-data-fallback-pattern",
                "patchAction", "FALLBACK",
                "decision", "RECOVER",
                "matrix", Map.of("m1", 3, "m2", 2, "m3", 1)));
        ObjectProvider<FailurePatternMemoryService> memoryProvider = mock(ObjectProvider.class);
        when(memoryProvider.getIfAvailable()).thenReturn(service);
        Orchestrator orchestrator = new Orchestrator();
        ReflectionTestUtils.setField(orchestrator, "failurePatternMemory", memoryProvider);

        try (TraceContext ignored = TraceContext.attach("recovery-test", "recall")) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("query", "private customer query sk-" + "redactioncontract1234567890");
            state.put("rag.retrieve", List.of("doc1"));
            state.put("citations", List.of("c1"));

            orchestrator.execute("brave.v1", state, null);
        }

        Object events = TraceStore.get("orch.events.v1");
        assertTrue(events instanceof List<?>);
        Map<String, Object> event = ((List<?>) events).stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) row)
                .filter(row -> "agent.judgment".equals(row.get("kind")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> data = (Map<String, Object>) event.get("data");
        Map<String, Object> recall = (Map<String, Object>) data.get("memoryRecall");

        assertEquals(1, recall.get("matchCount"));
        assertEquals("FALLBACK", recall.get("recommendedAction"));
        assertTrue(String.valueOf(recall.get("topPatternId")).length() >= 12);
        assertEquals(1, TraceStore.get("agent.breadcrumb.memory.recall.matchCount"));
        String rendered = String.valueOf(events);
        assertFalse(rendered.contains("private customer query"));
        assertFalse(rendered.contains("sk-redactioncontract"));
        assertFalse(rendered.contains("recovery-test"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoveryDecisionAppendsRedactedJudgmentTimelineBreadcrumb() {
        try (TraceContext trace = TraceContext.attach("recovery-test", "judgment")) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("query", "private customer query sk-" + "redactioncontract1234567890");
            state.put("rag.retrieve", List.of("doc1"));
            state.put("citations", List.of("c1"));

            new Orchestrator().execute("brave.v1", state, null);

            Object events = TraceStore.get("orch.events.v1");
            assertTrue(events instanceof List<?>);
            Map<String, Object> event = ((List<?>) events).stream()
                    .filter(Map.class::isInstance)
                    .map(row -> (Map<String, Object>) row)
                    .filter(row -> "agent.judgment".equals(row.get("kind")))
                    .findFirst()
                    .orElseThrow();
            Map<String, Object> data = (Map<String, Object>) event.get("data");
            Map<String, Object> rawTile = (Map<String, Object>) data.get("rawTile");

            assertEquals("recovery", event.get("phase"));
            assertEquals("FALLBACK", data.get("action"));
            assertEquals("RECOVER", data.get("decision"));
            assertEquals("DATA", data.get("failureClass"));
            assertEquals(1, data.get("round"));
            assertEquals("agent_judgment_raw_tile", rawTile.get("kind"));
            assertEquals(Boolean.FALSE, rawTile.get("rawPayloadStored"));
            assertTrue(String.valueOf(rawTile.get("patternId")).startsWith("hash:"));
            String rendered = String.valueOf(events);
            assertFalse(rendered.contains("private customer query"));
            assertFalse(rendered.contains("sk-redactioncontract"));
            assertFalse(rendered.contains("recovery-test"));
            assertRoundCount(trace, 1);
        }
    }

    @Test
    void invalidRecoveryRoundInputFallsBackToFirstRound() {
        try (TraceContext trace = TraceContext.attach("recovery-test", "bad-round")) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("recovery.round", "private-round-token");
            state.put("rag.retrieve", List.of("doc1"));
            state.put("citations", List.of("c1"));

            Map<String, Object> out = new Orchestrator().execute("brave.v1", state, null);

            assertEquals(1, out.get("recovery.round"));
            assertEquals(Boolean.TRUE, TraceStore.get("agent.orchestrator.suppressed"));
            assertEquals("recovery.round", TraceStore.get("agent.orchestrator.suppressed.stage"));
            assertEquals("invalid_number", TraceStore.get("agent.orchestrator.suppressed.errorType"));
            assertTrue(String.valueOf(TraceStore.getAll()).contains("valueHash"));
            assertTrue(!String.valueOf(TraceStore.getAll()).contains("private-round-token"));
            assertRoundCount(trace, 1);
        }
    }

    @Test
    void budgetFailureDegradesAndRecordsNextFlow() {
        try (TraceContext trace = TraceContext.attach("recovery-test", "budget").startWithBudget(Duration.ofMillis(100))) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("flow", "hypernova.v2");
            state.put("rag.retrieve", List.of("doc1", "doc2", "doc3"));
            state.put("citations", List.of("c1", "c2", "c3"));

            Map<String, Object> out = new Orchestrator().execute("hypernova.v2", state, null);

            assertEquals(1, out.get("recovery.round"));
            assertEquals("DEGRADE", out.get("recovery.action"));
            assertEquals("safe_autorun.v1", out.get("recovery.nextFlow"));
            assertRoundCount(trace, 1);
        }
    }

    @Test
    void maxRoundsEscalatesOnNextRecoverVerdict() {
        RecoveryPolicy policy = RecoveryPolicy.fromYaml("""
                recovery:
                  max-rounds: 1
                  min-citations: 3
                  budget-soft-ms: 500
                """);
        try (TraceContext trace = TraceContext.attach("recovery-test", "escalate")) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("recovery.round", 1);
            state.put("verdict", new Verdict(
                    Verdict.Decision.RECOVER,
                    FailureClass.DATA,
                    0.7,
                    "forced second round",
                    Map.of("citations", 1)));

            Map<String, Object> out = orchestrator(policy).execute("brave.v1", state, null);

            assertEquals(2, out.get("recovery.round"));
            assertEquals("ESCALATE", out.get("recovery.action"));
            assertEquals(Boolean.TRUE, out.get("recovery.escalated"));
            assertEquals(Boolean.TRUE, trace.getFlag("recovery.escalated"));
            assertRoundCount(trace, 1);
        }
    }

    @Test
    void policyDeniedAbortsAndEscalates() {
        try (TraceContext trace = TraceContext.attach("recovery-test", "policy")) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("policy.denied", true);
            state.put("rag.retrieve", List.of("doc1", "doc2", "doc3"));
            state.put("citations", List.of("c1", "c2", "c3"));

            Map<String, Object> out = new Orchestrator().execute("brave.v1", state, null);

            Verdict verdict = (Verdict) out.get("verdict");
            assertEquals(Verdict.Decision.ABORT, verdict.decision());
            assertEquals(FailureClass.POLICY, verdict.failureClass());
            assertEquals("ESCALATE", out.get("recovery.action"));
            assertEquals(Boolean.TRUE, out.get("recovery.escalated"));
            assertEquals(Boolean.TRUE, trace.getFlag("recovery.escalated"));
            assertRoundCount(trace, 1);
        }
    }

    @SuppressWarnings("unchecked")
    private void assertRoundCount(TraceContext trace, int expected) {
        Object raw = trace.getFlag("recovery.rounds");
        assertTrue(raw instanceof List<?>);
        assertEquals(expected, ((List<RecoveryRound>) raw).size());
    }

    private Orchestrator orchestrator(RecoveryPolicy policy) {
        return new Orchestrator(new DefaultRecoveryExecutor(policy, null), new CriticNode(policy), policy);
    }
}

package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.integrations.HybridRetriever;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.energy.ContradictionScorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CounterEvidenceRetrieveToolTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void retrievesExactlyThreeCounterSlotsWithinDocumentBudgetWithoutLeakingRawInputs() {
        AtomicInteger calls = new AtomicInteger();
        HybridRetriever retriever = new HybridRetriever() {
            @Override
            public List<Map<String, Object>> retrieve(String query, Integer topK, String domain) {
                throw new AssertionError("external-capable retrieve path must not be used");
            }

            @Override
            public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                int call = calls.incrementAndGet();
                return List.of(
                        Map.of("id", "doc-" + call + "-a", "source", "kb/source-" + call,
                                "snippet", "private evidence snippet " + call, "score", 0.9d),
                        Map.of("id", "doc-" + call + "-b", "source", "kb/source-" + call,
                                "snippet", "second private snippet " + call, "score", 0.7d));
            }
        };
        CounterEvidencePacketStore packetStore = new CounterEvidencePacketStore();
        CounterEvidenceRetrieveTool tool = new CounterEvidenceRetrieveTool(retriever, packetStore);

        ToolResponse response = tool.execute(new ToolRequest(Map.of(
                "originalClaim", "private original claim",
                "decisionQuestion", "private decision question",
                "queries", List.of(
                        Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "private contradiction query"),
                        Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "private alternative query"),
                        Map.of("slot", "PROVENANCE_AND_TIME", "query", "private boundary query")),
                "retrievalBudget", Map.of("maxDocuments", 4, "maxMillis", 5_000)), null));

        assertEquals(3, calls.get());
        assertEquals(3, ((List<?>) response.data().get("queryTraceRefs")).size());
        List<?> rows = (List<?>) response.data().get("evidenceRows");
        assertEquals(4, rows.size());
        assertTrue(rows.stream().allMatch(row -> String.valueOf(row).contains("relation=NEUTRAL")));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertEquals("VERIFIER_DEFERRED", response.data().get("decision"));
        assertTrue(((Number) response.data().get("retrievalConfidenceScore")).doubleValue() > 0.0d);
        assertTrue(String.valueOf(response.data().get("packetRef")).matches("hash:[0-9a-f]{12}"));

        String publicPayload = response.data() + TraceStore.getByPrefix("counterEvidence.").toString();
        assertFalse(publicPayload.contains("private original claim"), publicPayload);
        assertFalse(publicPayload.contains("private decision question"), publicPayload);
        assertFalse(publicPayload.contains("private contradiction query"), publicPayload);
        assertFalse(publicPayload.contains("private evidence snippet"), publicPayload);
    }

    @Test
    void rejectsMissingOrDuplicateCanonicalSlotsBeforeRetrieval() {
        CounterEvidenceRetrieveTool tool = new CounterEvidenceRetrieveTool(
                new HybridRetriever(), new CounterEvidencePacketStore());

        ToolInvocationException error = assertThrows(ToolInvocationException.class,
                () -> tool.execute(new ToolRequest(Map.of(
                        "originalClaim", "claim",
                        "decisionQuestion", "question",
                        "queries", List.of(
                                Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                                Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q2"),
                                Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3")),
                        "retrievalBudget", Map.of("maxDocuments", 3, "maxMillis", 100)), null)));

        assertEquals("counter_query_slots_invalid", error.code());
    }

    @Test
    void oneRetrieverFailureIsRedactedAndDoesNotSuppressRemainingCounterSlots() {
        AtomicInteger calls = new AtomicInteger();
        HybridRetriever retriever = new HybridRetriever() {
            @Override
            public List<Map<String, Object>> retrieve(String query, Integer topK, String domain) {
                throw new AssertionError("external-capable retrieve path must not be used");
            }

            @Override
            public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                int call = calls.incrementAndGet();
                if (call == 2) {
                    throw new IllegalStateException("private provider failure");
                }
                return List.of(Map.of(
                        "id", "doc-" + call,
                        "source", "source-" + call,
                        "snippet", "private snippet " + call,
                        "score", 0.8d));
            }
        };
        CounterEvidenceRetrieveTool tool = new CounterEvidenceRetrieveTool(
                retriever, new CounterEvidencePacketStore());

        ToolResponse response = tool.execute(new ToolRequest(Map.of(
                "originalClaim", "claim",
                "decisionQuestion", "question",
                "queries", List.of(
                        Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                        Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                        Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3")),
                "retrievalBudget", Map.of("maxDocuments", 3, "maxMillis", 5_000)), null));

        assertEquals(3, calls.get());
        assertEquals(3, response.data().get("counterQueryUsed"));
        assertEquals(2, ((List<?>) response.data().get("evidenceRows")).size());
        assertEquals("retrieval_failed",
                TraceStore.get("counterEvidence.query.alternative_or_unknown.status"));
        String publicPayload = response.data() + TraceStore.getByPrefix("counterEvidence.").toString();
        assertFalse(publicPayload.contains("private provider failure"), publicPayload);
        assertFalse(publicPayload.contains("private snippet"), publicPayload);
    }

    @Test
    void propagatesBudgetAndPolicyFailuresInsteadOfMisclassifyingThemAsEmptyEvidence() {
        HybridRetriever budgetRetriever = new HybridRetriever() {
            @Override
            public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                throw new ToolInvocationException(408, "tool_budget_exhausted");
            }
        };
        CounterEvidenceRetrieveTool budgetTool = new CounterEvidenceRetrieveTool(
                budgetRetriever, new CounterEvidencePacketStore());

        ToolInvocationException budgetError = assertThrows(ToolInvocationException.class,
                () -> budgetTool.execute(request()));
        assertEquals(408, budgetError.status());
        assertEquals("tool_budget_exhausted", budgetError.code());

        HybridRetriever policyRetriever = new HybridRetriever() {
            @Override
            public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                throw new SecurityException("private policy detail");
            }
        };
        CounterEvidenceRetrieveTool policyTool = new CounterEvidenceRetrieveTool(
                policyRetriever, new CounterEvidencePacketStore());

        assertThrows(SecurityException.class, () -> policyTool.execute(request()));
    }

    @Test
    void discardsLateFinalSlotResultsAndReportsActualBudgetAccounting() {
        AtomicInteger calls = new AtomicInteger();
        HybridRetriever retriever = new HybridRetriever() {
            @Override
            public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                if (calls.incrementAndGet() < 3) {
                    return List.of();
                }
                try {
                    Thread.sleep(130L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("interrupted");
                }
                return List.of(Map.of(
                        "id", "late-doc",
                        "source", "late-source",
                        "snippet", "private late evidence",
                        "score", 0.9d));
            }
        };
        CounterEvidenceRetrieveTool tool = new CounterEvidenceRetrieveTool(
                retriever, new CounterEvidencePacketStore());

        ToolResponse response = tool.execute(new ToolRequest(Map.of(
                "originalClaim", "claim",
                "decisionQuestion", "question",
                "queries", List.of(
                        Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                        Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                        Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3")),
                "retrievalBudget", Map.of("maxDocuments", 3, "maxMillis", 100)), null));

        assertEquals(3, calls.get());
        assertEquals(List.of(), response.data().get("evidenceRows"));
        assertEquals(true, response.data().get("retrievalBudgetExhausted"));
        assertEquals(0, response.data().get("counterRetrievedDocumentCount"));
        assertEquals(0, response.data().get("counterDocumentUsed"));
        assertEquals(0, response.data().get("counterDuplicateDocumentCount"));
        assertEquals(0, response.data().get("counterRetrievalFailureCount"));
        assertTrue(((Number) response.data().get("counterElapsedMillis")).longValue() >= 90L);
    }

    @Test
    void returnsNearBudgetEvenWhenRetrieverIgnoresInterrupts() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        HybridRetriever retriever = new HybridRetriever() {
            @Override
            public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                try {
                    while (true) {
                        try {
                            if (release.await(10L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                return List.of();
                            }
                        } catch (InterruptedException ignored) {
                            // Characterize an uncooperative blocking dependency.
                        }
                    }
                } finally {
                    finished.countDown();
                }
            }
        };
        CounterEvidenceRetrieveTool tool = new CounterEvidenceRetrieveTool(
                retriever, new CounterEvidencePacketStore());
        long started = System.nanoTime();

        try {
            ToolResponse response = tool.execute(new ToolRequest(Map.of(
                    "originalClaim", "claim",
                    "decisionQuestion", "question",
                    "queries", queries("q1"),
                    "retrievalBudget", Map.of("maxDocuments", 3, "maxMillis", 50)), null));

            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsed < 500L, "elapsed=" + elapsed);
            assertEquals(1, response.data().get("counterQueryUsed"));
            assertEquals(true, response.data().get("retrievalBudgetExhausted"));
        } finally {
            release.countDown();
            assertTrue(finished.await(1L, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void saturatedInterruptIgnoringWorkersRejectWithoutQueueingAndRecoverAfterRelease() throws Exception {
        java.util.concurrent.CountDownLatch stuckStarted = new java.util.concurrent.CountDownLatch(3);
        java.util.concurrent.CountDownLatch releaseStuck = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch stuckFinished = new java.util.concurrent.CountDownLatch(3);
        HybridRetriever stuckRetriever = new HybridRetriever() {
            @Override
            public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                stuckStarted.countDown();
                try {
                    while (true) {
                        try {
                            if (releaseStuck.await(10L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                return List.of();
                            }
                        } catch (InterruptedException ignored) {
                            // Deliberately ignore cancellation to model a stuck dependency.
                        }
                    }
                } finally {
                    stuckFinished.countDown();
                }
            }
        };

        try {
            for (int i = 0; i < 3; i++) {
                ToolResponse timedOut = new CounterEvidenceRetrieveTool(
                        stuckRetriever, new CounterEvidencePacketStore())
                        .execute(requestWithBudget(40));
                assertEquals(true, timedOut.data().get("retrievalBudgetExhausted"));
            }
            assertTrue(stuckStarted.await(1L, java.util.concurrent.TimeUnit.SECONDS));

            AtomicInteger quickCalls = new AtomicInteger();
            HybridRetriever quickRetriever = new HybridRetriever() {
                @Override
                public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                    quickCalls.incrementAndGet();
                    return List.of();
                }
            };
            CounterEvidenceRetrieveTool quickTool = new CounterEvidenceRetrieveTool(
                    quickRetriever, new CounterEvidencePacketStore());
            long started = System.nanoTime();

            ToolResponse saturated = quickTool.execute(requestWithBudget(400));

            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);
            assertTrue(elapsed < 200L, "saturated call queued for elapsed=" + elapsed);
            assertEquals(0, quickCalls.get());
            assertEquals(0, saturated.data().get("counterQueryUsed"));
            assertEquals(true, saturated.data().get("retrievalBudgetExhausted"));
            assertEquals("retrieval_capacity_exhausted",
                    TraceStore.get("counterEvidence.query.authoritative_constraint.status"));

            releaseStuck.countDown();
            assertTrue(stuckFinished.await(1L, java.util.concurrent.TimeUnit.SECONDS));

            ToolResponse recovered = awaitSuccessfulRetrieval(quickTool, 400, 1_000L);
            assertTrue(quickCalls.get() >= 3);
            assertEquals(3, recovered.data().get("counterQueryUsed"));
            assertEquals(false, recovered.data().get("retrievalBudgetExhausted"));
        } finally {
            releaseStuck.countDown();
            stuckFinished.await(1L, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void locallyCrossValidatesNumericCounterEvidenceWithoutCallingAnExternalModel() {
        HybridRetriever retriever = new HybridRetriever() {
            @Override
            public List<Map<String, Object>> retrieve(String query, Integer topK, String domain) {
                throw new AssertionError("external-capable retrieve path must not be used");
            }

            @Override
            public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                return query.equals("q1")
                        ? List.of(Map.of(
                                "id", "capacity-conflict",
                                "source", "local-spec",
                                "snippet", "The device capacity is 64 GB.",
                                "score", 0.9d))
                        : List.of();
            }
        };
        CounterEvidenceRetrieveTool tool = new CounterEvidenceRetrieveTool(
                retriever, new CounterEvidencePacketStore());

        ToolResponse response = tool.execute(new ToolRequest(Map.of(
                "originalClaim", "The device capacity is 32 GB.",
                "decisionQuestion", "Which capacity is supported?",
                "queries", List.of(
                        Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                        Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                        Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3")),
                "retrievalBudget", Map.of("maxDocuments", 3, "maxMillis", 5_000)), null));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.data().get("evidenceRows");
        assertEquals(1, rows.size());
        assertEquals("CONFLICTS", rows.get(0).get("relation"));
        assertTrue(((Number) rows.get(0).get("crossValidationScore")).doubleValue() >= 0.65d);
        assertEquals("LOCAL_RAG_HEURISTIC", response.data().get("crossValidationMode"));
        assertEquals(1, response.data().get("counterConflictSignalCount"));
        assertFalse(response.toString().contains("64 GB"), response.toString());
    }

    @Test
    void consumesMatchingOperatorAuthorityOnceAndPreservesItAfterMismatch() {
        AtomicInteger calls = new AtomicInteger();
        HybridRetriever retriever = new HybridRetriever() {
            @Override
            public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        OperatorProbeAuthorityStore authorityStore = new OperatorProbeAuthorityStore();
        CounterEvidenceRetrieveTool tool = new CounterEvidenceRetrieveTool(
                retriever,
                new CounterEvidencePacketStore(),
                new ContradictionScorer(),
                authorityStore);
        String authorityRef = authorityStore.issue(
                "operator-session",
                com.example.lms.trace.SafeRedactor.hashValue("claim"),
                "hash:222222222222",
                CounterEvidenceRetrieveTool.canonicalRequestHash(
                        "claim", "question", queries("q1"), retrievalBudget()),
                OperatorProbeAuthorityStore.Mode.PROBE_AND_PATCH_CANDIDATE,
                500);

        ToolInvocationException mismatch = assertThrows(ToolInvocationException.class,
                () -> tool.execute(authorizedRequest(authorityRef, "unrelated-query")));
        assertEquals("operator_authority_unavailable", mismatch.code());
        assertEquals(0, calls.get());

        ToolResponse response = tool.execute(authorizedRequest(authorityRef, "q1"));
        assertEquals(true, response.data().get("operatorAuthorityConsumed"));
        assertEquals("PROBE_AND_PATCH_CANDIDATE", response.data().get("operatorAuthorityMode"));
        assertEquals(3, calls.get());

        ToolInvocationException replay = assertThrows(ToolInvocationException.class,
                () -> tool.execute(authorizedRequest(
                        authorityRef, "q1")));
        assertEquals("operator_authority_unavailable", replay.code());
        assertEquals(3, calls.get());
    }

    private static ToolRequest request() {
        return new ToolRequest(Map.of(
                "originalClaim", "claim",
                "decisionQuestion", "question",
                "queries", List.of(
                        Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                        Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                        Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3")),
                "retrievalBudget", Map.of("maxDocuments", 3, "maxMillis", 5_000)), null);
    }

    private static ToolRequest requestWithBudget(int maxMillis) {
        return new ToolRequest(Map.of(
                "originalClaim", "claim",
                "decisionQuestion", "question",
                "queries", queries("q1"),
                "retrievalBudget", Map.of("maxDocuments", 3, "maxMillis", maxMillis)), null);
    }

    private static ToolResponse awaitSuccessfulRetrieval(CounterEvidenceRetrieveTool tool,
                                                         int requestBudgetMillis,
                                                         long timeoutMillis) {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        ToolResponse latest = null;
        while (System.nanoTime() < deadline) {
            latest = tool.execute(requestWithBudget(requestBudgetMillis));
            if (Integer.valueOf(3).equals(latest.data().get("counterQueryUsed"))
                    && Boolean.FALSE.equals(latest.data().get("retrievalBudgetExhausted"))) {
                return latest;
            }
            Thread.yield();
        }
        throw new AssertionError("retrieval workers did not recover before deadline: "
                + (latest == null ? "no response" : latest.data().get("counterQueryUsed")));
    }

    private static ToolRequest authorizedRequest(String authorityRef, String firstQuery) {
        return new ToolRequest(Map.of(
                "operatorAuthorityRef", authorityRef,
                "operatorConstraintHash", "hash:222222222222",
                "originalClaim", "claim",
                "decisionQuestion", "question",
                "queries", queries(firstQuery),
                "retrievalBudget", retrievalBudget()),
                new ToolContext("operator-session", null));
    }

    private static List<Map<String, String>> queries(String firstQuery) {
        return List.of(
                Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", firstQuery),
                Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3"));
    }

    private static Map<String, Integer> retrievalBudget() {
        return Map.of("maxDocuments", 3, "maxMillis", 5_000);
    }
}

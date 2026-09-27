package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.integrations.HybridRetriever;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one bounded, local-only counter-evidence retrieval pass. Query text and
 * evidence snippets stay inside the retriever boundary; the public tool result
 * contains only hashes, low-cardinality labels, counts, and normalized rows.
 */
@RequiresScopes({ToolScope.INTERNAL_READ})
public final class CounterEvidenceRetrieveTool implements AgentTool {

    private static final int MAX_TEXT_LENGTH = 4_096;
    private static final int MAX_QUERY_LENGTH = 512;
    private static final int MAX_DOCUMENTS = 9;
    private static final int MAX_MILLIS = 60_000;
    private static final AtomicInteger RETRIEVAL_THREAD_SEQUENCE = new AtomicInteger();
    private static final ExecutorService LOCAL_RETRIEVAL_EXECUTOR = new ThreadPoolExecutor(
            3,
            3,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            task -> {
                Thread thread = new Thread(
                        task,
                        "counter-evidence-local-" + RETRIEVAL_THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private final HybridRetriever retriever;
    private final CounterEvidencePacketStore packetStore;
    private final ContradictionScorer contradictionScorer;
    private final OperatorProbeAuthorityStore authorityStore;
    private final boolean authorityRequired;

    public CounterEvidenceRetrieveTool(HybridRetriever retriever) {
        this(retriever, new CounterEvidencePacketStore(), new ContradictionScorer(), null, false);
    }

    public CounterEvidenceRetrieveTool(HybridRetriever retriever,
                                       CounterEvidencePacketStore packetStore) {
        this(retriever, packetStore, new ContradictionScorer(), null, false);
    }

    public CounterEvidenceRetrieveTool(HybridRetriever retriever,
                                       CounterEvidencePacketStore packetStore,
                                       ContradictionScorer contradictionScorer) {
        this(retriever, packetStore, contradictionScorer, null, false);
    }

    public CounterEvidenceRetrieveTool(HybridRetriever retriever,
                                       CounterEvidencePacketStore packetStore,
                                       ContradictionScorer contradictionScorer,
                                       OperatorProbeAuthorityStore authorityStore) {
        this(retriever, packetStore, contradictionScorer, authorityStore, true);
    }

    private CounterEvidenceRetrieveTool(HybridRetriever retriever,
                                        CounterEvidencePacketStore packetStore,
                                        ContradictionScorer contradictionScorer,
                                        OperatorProbeAuthorityStore authorityStore,
                                        boolean authorityRequired) {
        this.retriever = retriever;
        this.packetStore = packetStore;
        this.contradictionScorer = contradictionScorer == null
                ? new ContradictionScorer()
                : contradictionScorer;
        this.authorityStore = authorityStore;
        this.authorityRequired = authorityRequired;
    }

    @Override
    public String id() {
        return "counter.evidence.retrieve";
    }

    @Override
    public String description() {
        return "Retrieve exactly three bounded local-RAG counter-evidence slots without exposing raw claims or queries.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        String originalClaim = requiredText(input.get("originalClaim"), "original_claim_required", MAX_TEXT_LENGTH);
        String decisionQuestion = requiredText(input.get("decisionQuestion"), "decision_question_required", MAX_TEXT_LENGTH);
        EnumMap<QuerySlot, String> queries = parseQueries(input.get("queries"));
        RetrievalBudget budget = parseBudget(input.get("retrievalBudget"));
        OperatorProbeAuthorityStore.Authority operatorAuthority = null;
        if (authorityRequired) {
            String operatorSessionId = requiredSessionId(request);
            String authorityRef = requiredOpaqueRef(
                    input.get("operatorAuthorityRef"), "operator_authority_ref_required");
            String constraintHash = requiredOpaqueRef(
                    input.get("operatorConstraintHash"), "operator_constraint_hash_required");
            String goalHash = input.get("operatorGoalHash") == null
                    ? SafeRedactor.hashValue(originalClaim)
                    : requiredOpaqueRef(input.get("operatorGoalHash"), "operator_goal_hash_invalid");
            String probeRequestHash = canonicalRequestHash(
                    originalClaim,
                    decisionQuestion,
                    input.get("queries"),
                    input.get("retrievalBudget"));
            operatorAuthority = authorityStore.consumeIfMatches(
                            authorityRef,
                            operatorSessionId,
                            goalHash,
                            constraintHash,
                            probeRequestHash,
                            OperatorProbeAuthorityStore.Mode.PROBE_AND_PATCH_CANDIDATE)
                    .orElseThrow(() -> ToolInvocationException.forbidden("operator_authority_unavailable"));
            budget = new RetrievalBudget(
                    budget.maxDocuments(),
                    Math.min(budget.maxMillis(), operatorAuthority.maxMillis()));
        }

        TraceStore.put("counterEvidence.claimHash", SafeRedactor.hashValue(originalClaim));
        TraceStore.put("counterEvidence.decisionQuestionHash", SafeRedactor.hashValue(decisionQuestion));
        TraceStore.put("counterEvidence.requestedQueryCount", QuerySlot.values().length);
        TraceStore.put("counterEvidence.maxDocuments", budget.maxDocuments());
        TraceStore.put("counterEvidence.maxMillis", budget.maxMillis());

        long started = System.nanoTime();
        int remainingDocuments = budget.maxDocuments();
        List<String> queryTraceRefs = new ArrayList<>(QuerySlot.values().length);
        List<Map<String, Object>> evidenceRows = new ArrayList<>();
        Set<String> evidenceKeys = new HashSet<>();
        Set<String> independentSources = new HashSet<>();
        int coveredSlots = 0;
        int queryAttempts = 0;
        int retrievedDocumentCount = 0;
        int duplicateDocumentCount = 0;
        int retrievalFailureCount = 0;
        boolean budgetExhausted = false;

        QuerySlot[] slots = QuerySlot.values();
        for (int i = 0; i < slots.length; i++) {
            QuerySlot slot = slots[i];
            String query = queries.get(slot);
            String queryTraceRef = ref(slot.name() + ":" + query);
            queryTraceRefs.add(queryTraceRef);
            TraceStore.put("counterEvidence.query." + slot.traceLabel() + ".traceRef", queryTraceRef);

            if (remainingDocuments <= 0 || elapsedMillis(started) >= budget.maxMillis()
                    || TraceContext.current().remainingMillis() == 0L) {
                budgetExhausted = true;
                TraceStore.put("counterEvidence.query." + slot.traceLabel() + ".status", "budget_exhausted");
                continue;
            }

            int remainingSlots = slots.length - i;
            int perQueryLimit = Math.max(1, (remainingDocuments + remainingSlots - 1) / remainingSlots);
            perQueryLimit = Math.min(perQueryLimit, 3);
            List<Map<String, Object>> safe;
            try {
                long remainingLocalMillis = Math.max(1L, budget.maxMillis() - elapsedMillis(started));
                long remainingTraceMillis = TraceContext.current().remainingMillis();
                long callBudgetMillis = remainingTraceMillis == Long.MAX_VALUE
                        ? remainingLocalMillis
                        : Math.max(1L, Math.min(remainingLocalMillis, remainingTraceMillis));
                Future<List<Map<String, Object>>> retrieval = submitRetrieval(
                        retriever, query, perQueryLimit);
                queryAttempts++;
                List<Map<String, Object>> retrieved = awaitRetrievalWithinBudget(
                        retrieval, callBudgetMillis);
                safe = retrieved == null ? List.of() : retrieved;
            } catch (RejectedExecutionException error) {
                budgetExhausted = true;
                TraceStore.put("counterEvidence.query." + slot.traceLabel() + ".status",
                        "retrieval_capacity_exhausted");
                continue;
            } catch (TimeoutException error) {
                budgetExhausted = true;
                TraceStore.put("counterEvidence.query." + slot.traceLabel() + ".status",
                        "budget_exhausted_during_retrieval");
                continue;
            } catch (ToolInvocationException | SecurityException | CancellationException error) {
                throw error;
            } catch (RuntimeException error) {
                TraceStore.put("counterEvidence.query." + slot.traceLabel() + ".status", "retrieval_failed");
                TraceStore.put("counterEvidence.query." + slot.traceLabel() + ".errorType",
                        SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown"));
                TraceStore.inc("counterEvidence.retrievalFailureCount");
                retrievalFailureCount++;
                continue;
            }
            retrievedDocumentCount += safe.size();
            if (elapsedMillis(started) >= budget.maxMillis()
                    || TraceContext.current().remainingMillis() == 0L) {
                budgetExhausted = true;
                TraceStore.put("counterEvidence.query." + slot.traceLabel() + ".status",
                        "budget_exhausted_after_retrieval");
                continue;
            }
            int acceptedForSlot = 0;
            for (Map<String, Object> result : safe) {
                if (remainingDocuments <= 0 || acceptedForSlot >= perQueryLimit) {
                    break;
                }
                Map<String, Object> row = normalize(
                        result, slot, queryTraceRef, originalClaim, contradictionScorer);
                String evidenceKey = String.valueOf(row.get("evidenceId"));
                if (!evidenceKeys.add(evidenceKey)) {
                    duplicateDocumentCount++;
                    continue;
                }
                evidenceRows.add(row);
                independentSources.add(String.valueOf(row.get("independenceGroupHash")));
                acceptedForSlot++;
                remainingDocuments--;
            }
            if (acceptedForSlot > 0) {
                coveredSlots++;
            }
            TraceStore.put("counterEvidence.query." + slot.traceLabel() + ".status",
                    acceptedForSlot == 0 ? "empty" : "retrieved");
            TraceStore.put("counterEvidence.query." + slot.traceLabel() + ".acceptedCount", acceptedForSlot);
        }

        double confidence = round3((coveredSlots / 3.0d) * 0.65d
                + Math.min(1.0d, independentSources.size() / 3.0d) * 0.35d);
        TraceStore.put("counterEvidence.returnedDocumentCount", evidenceRows.size());
        TraceStore.put("counterEvidence.coveredSlotCount", coveredSlots);
        TraceStore.put("counterEvidence.independentSourceCount", independentSources.size());
        TraceStore.put("counterEvidence.retrievalConfidenceScore", confidence);
        TraceStore.put("counterEvidence.budgetExhausted", budgetExhausted);
        TraceStore.put("counterEvidence.verificationGatePassed", false);
        long elapsedMillis = elapsedMillis(started);
        TraceStore.put("counterEvidence.queryAttemptCount", queryAttempts);
        TraceStore.put("counterEvidence.retrievedDocumentCount", retrievedDocumentCount);
        TraceStore.put("counterEvidence.acceptedDocumentCount", evidenceRows.size());
        TraceStore.put("counterEvidence.duplicateDocumentCount", duplicateDocumentCount);
        TraceStore.put("counterEvidence.retrievalFailureCount", retrievalFailureCount);
        TraceStore.put("counterEvidence.elapsedMillis", elapsedMillis);
        int conflictSignalCount = (int) evidenceRows.stream()
                .filter(row -> "CONFLICTS".equals(row.get("relation")))
                .count();
        TraceStore.put("counterEvidence.crossValidationMode", "local_rag_heuristic");
        TraceStore.put("counterEvidence.conflictSignalCount", conflictSignalCount);

        String claimHash = SafeRedactor.hashValue(originalClaim);
        String decisionQuestionHash = SafeRedactor.hashValue(decisionQuestion);
        String packetRef = packetStore.issue(sessionId(request), claimHash, decisionQuestionHash,
                queryTraceRefs, evidenceRows);

        ToolResponse response = ToolResponse.ok()
                .put("decision", "VERIFIER_DEFERRED")
                .put("decisionReason", "normalized_relation_metadata_required")
                .put("decisionAuthority", "retrieval_only")
                .put("verificationGatePassed", false)
                .put("retrievalConfidenceScore", confidence)
                .put("packetRef", packetRef)
                .put("queryTraceRefs", List.copyOf(queryTraceRefs))
                .put("evidenceRows", List.copyOf(evidenceRows))
                .put("counterQueryReserved", QuerySlot.values().length)
                .put("counterQueryUsed", queryAttempts)
                .put("counterDocumentReserved", budget.maxDocuments())
                .put("counterDocumentUsed", evidenceRows.size())
                .put("counterRetrievedDocumentCount", retrievedDocumentCount)
                .put("counterDuplicateDocumentCount", duplicateDocumentCount)
                .put("counterRetrievalFailureCount", retrievalFailureCount)
                .put("counterElapsedMillis", elapsedMillis)
                .put("crossValidationMode", "LOCAL_RAG_HEURISTIC")
                .put("counterConflictSignalCount", conflictSignalCount)
                .put("retrievalBudgetExhausted", budgetExhausted)
                .put("activationPlan", List.of(Map.of(
                        "skill", "verifier",
                        "action", "DEFER",
                        "trigger", "complete_normalized_relation_metadata_required")));
        if (operatorAuthority != null) {
            response.put("operatorAuthorityConsumed", true)
                    .put("operatorAuthorityMode", operatorAuthority.mode().name())
                    .put("operatorEffectiveMaxMillis", budget.maxMillis());
        }
        return response;
    }

    private static String sessionId(ToolRequest request) {
        return request == null || request.context() == null
                ? "internal-agent"
                : request.context().sessionId();
    }

    private static String requiredSessionId(ToolRequest request) {
        if (request == null || request.context() == null
                || request.context().sessionId() == null
                || request.context().sessionId().isBlank()) {
            throw ToolInvocationException.badRequest("agent_tool_session_required");
        }
        return request.context().sessionId().trim();
    }

    private static Future<List<Map<String, Object>>> submitRetrieval(HybridRetriever retriever,
                                                                     String query,
                                                                     int limit) {
        return LOCAL_RETRIEVAL_EXECUTOR.submit(
                () -> retriever.retrieveStrictLocal(query, limit));
    }

    private static List<Map<String, Object>> awaitRetrievalWithinBudget(
            Future<List<Map<String, Object>>> future,
            long maxMillis) throws TimeoutException {
        try {
            return future.get(Math.max(1L, maxMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw timeout;
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("counter_evidence_retrieval_interrupted");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ToolInvocationException toolError) {
                throw toolError;
            }
            if (cause instanceof SecurityException securityError) {
                throw securityError;
            }
            if (cause instanceof CancellationException cancellation) {
                throw cancellation;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("local_retrieval_failed", cause);
        }
    }

    private static Map<String, Object> normalize(Map<String, Object> result,
                                                 QuerySlot slot,
                                                 String queryTraceRef,
                                                 String originalClaim,
                                                 ContradictionScorer contradictionScorer) {
        Map<String, Object> safe = result == null ? Map.of() : result;
        String id = text(safe.get("id"));
        String source = text(safe.get("source"));
        String snippet = text(firstNonNull(safe.get("snippet"), safe.get("text"), safe.get("content")));
        String evidenceId = ref(id + "|" + source + "|" + snippet);
        String independenceGroupHash = ref(source.isBlank() ? evidenceId : source);
        double crossValidationScore = round3(contradictionScorer.scoreLocalOnly(originalClaim, snippet));
        String relation = crossValidationScore >= 0.65d ? "CONFLICTS" : "NEUTRAL";

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("evidenceId", evidenceId);
        row.put("querySlot", slot.name());
        row.put("queryTraceRef", queryTraceRef);
        row.put("observedAt", Instant.now().toString());
        row.put("validAt", "UNKNOWN");
        row.put("directness", "UNKNOWN");
        row.put("authority", "UNKNOWN");
        row.put("independenceGroupHash", independenceGroupHash);
        row.put("relation", relation);
        row.put("coverage", "PARTIAL");
        row.put("retrievalScore", normalizedScore(safe.get("score")));
        row.put("crossValidationScore", crossValidationScore);
        return Map.copyOf(row);
    }

    private static EnumMap<QuerySlot, String> parseQueries(Object raw) {
        if (!(raw instanceof List<?> rows) || rows.size() != QuerySlot.values().length) {
            throw ToolInvocationException.badRequest("counter_query_slots_invalid");
        }
        EnumMap<QuerySlot, String> queries = new EnumMap<>(QuerySlot.class);
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                throw ToolInvocationException.badRequest("counter_query_slots_invalid");
            }
            QuerySlot slot;
            try {
                slot = QuerySlot.valueOf(requiredText(map.get("slot"), "counter_query_slots_invalid", 64)
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw ToolInvocationException.badRequest("counter_query_slots_invalid");
            }
            String query = requiredText(map.get("query"), "counter_query_required", MAX_QUERY_LENGTH);
            if (queries.putIfAbsent(slot, query) != null) {
                throw ToolInvocationException.badRequest("counter_query_slots_invalid");
            }
        }
        if (queries.size() != QuerySlot.values().length) {
            throw ToolInvocationException.badRequest("counter_query_slots_invalid");
        }
        return queries;
    }

    private static RetrievalBudget parseBudget(Object raw) {
        if (!(raw instanceof Map<?, ?> budget)) {
            throw ToolInvocationException.badRequest("counter_retrieval_budget_required");
        }
        int maxDocuments = requiredInt(budget.get("maxDocuments"), 3, MAX_DOCUMENTS,
                "counter_document_budget_invalid");
        int maxMillis = requiredInt(budget.get("maxMillis"), 1, MAX_MILLIS,
                "counter_time_budget_invalid");
        return new RetrievalBudget(maxDocuments, maxMillis);
    }

    public static String canonicalRequestHash(String originalClaim,
                                              String decisionQuestion,
                                              Object rawQueries,
                                              Object rawBudget) {
        return CounterEvidenceRequestFingerprint.canonicalRequestHash(
                originalClaim, decisionQuestion, rawQueries, rawBudget);
    }

    private static String requiredText(Object raw, String errorCode, int maxLength) {
        String value = text(raw).trim();
        if (value.isEmpty() || value.length() > maxLength) {
            throw ToolInvocationException.badRequest(errorCode);
        }
        return value;
    }

    private static int requiredInt(Object raw, int min, int max, String errorCode) {
        try {
            int value = raw instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(raw).trim());
            if (value < min || value > max) {
                throw ToolInvocationException.badRequest(errorCode);
            }
            return value;
        } catch (ToolInvocationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw ToolInvocationException.badRequest(errorCode);
        }
    }

    private static String requiredOpaqueRef(Object raw, String errorCode) {
        String value = text(raw).trim();
        if (!value.matches("hash:[0-9a-f]{12}")) {
            throw ToolInvocationException.badRequest(errorCode);
        }
        return value;
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String ref(String value) {
        String hash = SafeRedactor.hashValue(value == null ? "" : value);
        return hash == null ? "" : hash.substring(0, Math.min(24, hash.length()));
    }

    private static double normalizedScore(Object raw) {
        try {
            double score = raw instanceof Number number ? number.doubleValue() : Double.parseDouble(text(raw));
            if (!Double.isFinite(score) || score <= 0.0d) {
                return 0.0d;
            }
            return round3(score <= 1.0d ? score : score / (1.0d + score));
        } catch (RuntimeException error) {
            return 0.0d;
        }
    }

    private static double round3(double value) {
        return Math.round(Math.max(0.0d, Math.min(1.0d, value)) * 1_000.0d) / 1_000.0d;
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private enum QuerySlot {
        AUTHORITATIVE_CONSTRAINT,
        ALTERNATIVE_OR_UNKNOWN,
        PROVENANCE_AND_TIME;

        String traceLabel() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record RetrievalBudget(int maxDocuments, int maxMillis) {
    }
}

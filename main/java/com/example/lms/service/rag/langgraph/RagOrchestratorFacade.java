package com.example.lms.service.rag.langgraph;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.service.ops.RagOpsLedgerService;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

@Component
public class RagOrchestratorFacade {

    private static final Logger log = LoggerFactory.getLogger(RagOrchestratorFacade.class);

    private final UnifiedRagOrchestrator legacyOrchestrator;
    private final ObjectProvider<RagGraphExecutor> graphExecutorProvider;
    private final RagGraphProperties properties;
    private RagOpsLedgerService opsLedgerService;

    @Autowired
    public RagOrchestratorFacade(UnifiedRagOrchestrator legacyOrchestrator,
                                 ObjectProvider<RagGraphExecutor> graphExecutorProvider,
                                 RagGraphProperties properties) {
        this.legacyOrchestrator = legacyOrchestrator;
        this.graphExecutorProvider = graphExecutorProvider;
        this.properties = properties;
    }

    public RagOrchestratorFacade(UnifiedRagOrchestrator legacyOrchestrator,
                                 RagGraphExecutor graphExecutor,
                                 RagGraphProperties properties) {
        this.legacyOrchestrator = legacyOrchestrator;
        this.graphExecutorProvider = new FixedObjectProvider<>(graphExecutor);
        this.properties = properties;
    }

    @Autowired(required = false)
    void setOpsLedgerService(RagOpsLedgerService opsLedgerService) {
        this.opsLedgerService = opsLedgerService;
    }

    public QueryResponse query(QueryRequest request) {
        com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
        long startedNanos = System.nanoTime();
        QueryRequest safeRequest = request == null ? new QueryRequest() : request;
        if (properties.isOff()) {
            QueryResponse response = legacyOrchestrator.query(safeRequest);
            ensureDebug(response).put("langgraph.mode", "off");
            recordOpsLedger(safeRequest, response, startedNanos);
            return response;
        }
        if (properties.isShadow()) {
            long legacyStartedNanos = startedNanos;
            QueryResponse legacyResponse = legacyOrchestrator.query(safeRequest);
            runShadow(safeRequest, legacyResponse, elapsedMs(legacyStartedNanos));
            recordOpsLedger(safeRequest, legacyResponse, startedNanos);
            return legacyResponse;
        }
        if (properties.isPrimary()) {
            try {
                QueryResponse response = requireGraphExecutor().execute(safeRequest);
                Map<String, Object> debug = ensureDebug(response);
                boolean invokeFallback = invokeFallbackTriggered(debug);
                int resultCount = resultCount(response);
                double promotionScore = promotionScore(debug, invokeFallback, 0.0d, resultCount, resultCount);
                List<String> promotionBlockers = promotionBlockers(debug, invokeFallback, 0.0d, resultCount, resultCount);
                boolean promotionEligible = promotionBlockers.isEmpty() && promotionScore >= 0.70d;
                debug.put("langgraph.mode", promotionEligible ? "primary" : "primary-degraded");
                debug.put("langgraph.primary.promotionEligible", promotionEligible);
                debug.put("langgraph.primary.promotionScore", promotionScore);
                debug.put("langgraph.primary.promotionBlockers", promotionBlockers);
                if (invokeFallback) {
                    debug.put("langgraph.primary.fallback", "compiled_graph_invoke");
                }
                recordOpsLedger(safeRequest, response, startedNanos);
                return response;
            } catch (Exception e) {
                // A caller interrupt is terminal; stage-only cancellation remains fail-soft.
                if (e instanceof CancellationException cancelled
                        && Thread.currentThread().isInterrupted()) {
                    throw cancelled;
                }
                com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
                QueryResponse fallback = legacyOrchestrator.query(safeRequest);
                Map<String, Object> debug = ensureDebug(fallback);
                debug.put("langgraph.mode", "primary-fallback");
                debug.put("langgraph.primary.fallback", operationalErrorType(e));
                debug.put("langgraph.primary.promotionEligible", false);
                debug.put("langgraph.primary.promotionScore", 0.0d);
                debug.put("langgraph.primary.promotionBlockers", java.util.List.of("graph_exception"));
                log.debug("[AWX2AF2][langgraph] primary fallback to legacy. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                recordGraphExceptionTrace("langgraph.primary", safeRequest, e, elapsedMs(startedNanos), resultCount(fallback));
                recordOpsLedger(safeRequest, fallback, startedNanos);
                return fallback;
            }
        }
        QueryResponse response = legacyOrchestrator.query(safeRequest);
        ensureDebug(response).put("langgraph.mode", "unknown-fallback");
        recordOpsLedger(safeRequest, response, startedNanos);
        return response;
    }

    private void recordOpsLedger(QueryRequest request, QueryResponse response, long startedNanos) {
        RagOpsLedgerService ledger = opsLedgerService;
        if (ledger == null) {
            return;
        }
        try {
            ledger.recordRagRun(request, response, elapsedMs(startedNanos));
        } catch (Exception e) {
            log.debug("[AWX2AF2][ops-ledger] RAG capture hook skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    private void runShadow(QueryRequest request, QueryResponse legacyResponse, long legacyLatencyMs) {
        long startedNanos = System.nanoTime();
        try {
            QueryResponse graphResponse = requireGraphExecutor().execute(request);
            long graphLatencyMs = elapsedMs(startedNanos);
            int graphCount = resultCount(graphResponse);
            int legacyCount = resultCount(legacyResponse);
            Map<String, Object> debug = ensureDebug(legacyResponse);
            Map<String, Object> graphDebug = graphResponse == null ? Map.of() : ensureDebug(graphResponse);
            boolean repairTriggered = graphDebug.containsKey("langgraph.node.repair");
            boolean fallbackTriggered = invokeFallbackTriggered(graphDebug);
            double answerDiffScore = answerDiffScore(legacyResponse, graphResponse);
            double promotionScore = promotionScore(graphDebug, fallbackTriggered, answerDiffScore, graphCount, legacyCount);
            java.util.List<String> promotionBlockers =
                    promotionBlockers(graphDebug, fallbackTriggered, answerDiffScore, graphCount, legacyCount);
            debug.put("langgraph.mode", "shadow");
            debug.put("langgraph.shadow.legacyResultCount", legacyCount);
            debug.put("langgraph.shadow.graphResultCount", graphCount);
            debug.put("langgraph.shadow.latencyMsLegacy", legacyLatencyMs);
            debug.put("langgraph.shadow.latencyMsGraph", graphLatencyMs);
            debug.put("langgraph.shadow.repairTriggered", repairTriggered);
            debug.put("langgraph.shadow.fallbackTriggered", fallbackTriggered);
            debug.put("langgraph.shadow.answerDiffScore", answerDiffScore);
            debug.put("langgraph.shadow.tookMs", graphLatencyMs);
            debug.put("langgraph.shadow.resultCount", graphCount);
            debug.put("langgraph.shadow.deltaResultCount", graphCount - legacyCount);
            debug.put("langgraph.shadow.requestId", graphResponse == null ? null : graphResponse.requestId);
            debug.put("langgraph.shadow.promotionScore", promotionScore);
            debug.put("langgraph.shadow.promotionEligible", promotionBlockers.isEmpty() && promotionScore >= 0.70d);
            debug.put("langgraph.shadow.promotionBlockers", promotionBlockers);
            copyShadowDebug(debug, graphDebug, "langgraph.node.repair", "langgraph.shadow.repairStatus");
            copyShadowDebug(debug, graphDebug, "langgraph.repair.added", "langgraph.shadow.repairAdded");
            copyShadowDebug(debug, graphDebug, "langgraph.emptyReason", "langgraph.shadow.emptyReason");
            copyShadowDebug(debug, graphDebug, "langgraph.failureReason", "langgraph.shadow.failureReason");
            copyShadowDebug(debug, graphDebug, "langgraph.invoke.trigger", "langgraph.shadow.invoke.trigger");
            copyShadowDebug(debug, graphDebug, "langgraph.invoke.failureClass", "langgraph.shadow.invoke.failureClass");
            copyShadowDebug(debug, graphDebug, "langgraph.invoke.source", "langgraph.shadow.invoke.source");
            copyShadowDebug(debug, graphDebug, "langgraph.checkpoint.backend", "langgraph.shadow.checkpoint.backend");
            copyShadowDebug(debug, graphDebug, "langgraph.checkpoint.enabled", "langgraph.shadow.checkpoint.enabled");
            copyShadowDebug(debug, graphDebug, "langgraph.control.mode", "langgraph.shadow.control.mode");
            copyShadowDebug(debug, graphDebug, "langgraph.control.reasonCode", "langgraph.shadow.control.reasonCode");
            copyShadowDebug(debug, graphDebug, "langgraph.control.failureAction", "langgraph.shadow.control.failureAction");
            copyShadowDebug(debug, graphDebug, "langgraph.control.policyRuleId", "langgraph.shadow.control.policyRuleId");
            copyShadowDebug(debug, graphDebug, "langgraph.control.transitionScore", "langgraph.shadow.control.transitionScore");
            copyShadowDebug(debug, graphDebug, "langgraph.control.promotionEligible", "langgraph.shadow.control.promotionEligible");
            copyShadowDebug(debug, graphDebug, "langgraph.control.promotionBlockers", "langgraph.shadow.control.promotionBlockers");
            copyShadowDebug(debug, graphDebug, "langgraph.transitionTrace", "langgraph.shadow.transitionTrace");
            log.debug("[AWX2AF2][langgraph][shadow] legacyResultCount={} graphResultCount={} latencyMsLegacy={} latencyMsGraph={} repairTriggered={} fallbackTriggered={} answerDiffScore={} promotionScore={}",
                    legacyCount, graphCount, legacyLatencyMs, graphLatencyMs, repairTriggered, fallbackTriggered, answerDiffScore, promotionScore);
        } catch (Exception e) {
            long graphLatencyMs = elapsedMs(startedNanos);
            int legacyCount = resultCount(legacyResponse);
            Map<String, Object> debug = ensureDebug(legacyResponse);
            debug.put("langgraph.mode", "shadow");
            debug.put("langgraph.shadow.legacyResultCount", legacyCount);
            debug.put("langgraph.shadow.graphResultCount", 0);
            debug.put("langgraph.shadow.latencyMsLegacy", legacyLatencyMs);
            debug.put("langgraph.shadow.latencyMsGraph", graphLatencyMs);
            debug.put("langgraph.shadow.repairTriggered", false);
            debug.put("langgraph.shadow.fallbackTriggered", true);
            debug.put("langgraph.shadow.answerDiffScore", 1.0d);
            debug.put("langgraph.shadow.tookMs", graphLatencyMs);
            debug.put("langgraph.shadow.resultCount", 0);
            debug.put("langgraph.shadow.deltaResultCount", -legacyCount);
            debug.put("langgraph.shadow.error", operationalErrorType(e));
            debug.put("langgraph.shadow.promotionScore", 0.0d);
            debug.put("langgraph.shadow.promotionEligible", false);
            debug.put("langgraph.shadow.promotionBlockers", java.util.List.of("graph_exception"));
            recordGraphExceptionTrace("langgraph.shadow", request, e, graphLatencyMs, legacyCount);
            log.debug("[AWX2AF2][langgraph][shadow] legacyResultCount={} graphResultCount=0 latencyMsLegacy={} latencyMsGraph={} repairTriggered=false fallbackTriggered=true answerDiffScore=1.0",
                    legacyCount, legacyLatencyMs, graphLatencyMs);
            log.debug("[AWX2AF2][langgraph] shadow comparison failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    private RagGraphExecutor requireGraphExecutor() {
        RagGraphExecutor executor = graphExecutorProvider.getIfAvailable();
        if (executor == null) {
            throw new IllegalStateException("LangGraph executor unavailable");
        }
        return executor;
    }

    private static void recordGraphExceptionTrace(String prefix,
                                                  QueryRequest request,
                                                  Exception e,
                                                  long tookMs,
                                                  int legacyResultCount) {
        String safePrefix = prefix == null || prefix.isBlank() ? "langgraph.facade" : prefix;
        String query = request == null ? null : request.query;
        String errorType = operationalErrorType(e);
        TraceStore.put(safePrefix + ".fallbackTriggered", true);
        TraceStore.put(safePrefix + ".failureClass", "graph_exception");
        TraceStore.put(safePrefix + ".errorType", errorType);
        TraceStore.put(safePrefix + ".queryHash12", SafeRedactor.hash12(query));
        TraceStore.put(safePrefix + ".queryLength", query == null ? 0 : query.length());
        TraceStore.put(safePrefix + ".tookMs", Math.max(0L, tookMs));
        TraceStore.put(safePrefix + ".legacyResultCount", Math.max(0, legacyResultCount));
        TraceStore.append(safePrefix + ".events", Map.of(
                "failureClass", "graph_exception",
                "errorType", errorType,
                "queryHash12", SafeRedactor.hash12(query),
                "queryLength", query == null ? 0 : query.length(),
                "tookMs", Math.max(0L, tookMs),
                "legacyResultCount", Math.max(0, legacyResultCount)));
    }

    private static String operationalErrorType(Exception e) {
        Throwable root = rootCause(e);
        String className = root == null ? "" : root.getClass().getSimpleName();
        String lowerClass = className.toLowerCase(Locale.ROOT);
        String message = root == null || root.getMessage() == null
                ? ""
                : root.getMessage().toLowerCase(Locale.ROOT);
        if (root instanceof CancellationException
                || root instanceof InterruptedException
                || lowerClass.contains("cancel")
                || lowerClass.contains("interrupt")
                || message.contains("cancelled")
                || message.contains("canceled")
                || message.contains("interrupted")) {
            return "cancelled";
        }
        return SafeRedactor.traceLabelOrFallback(className, "unknown");
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current.getCause() != null && current.getCause() != current && depth++ < 8) {
            current = current.getCause();
        }
        return current;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static int resultCount(QueryResponse response) {
        return response == null || response.results == null ? 0 : response.results.size();
    }

    private static boolean invokeFallbackTriggered(Map<String, Object> debug) {
        if (debug == null || debug.isEmpty()) {
            return false;
        }
        Object raw = debug.get("langgraph.invoke.fallbackTriggered");
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw != null && "true".equalsIgnoreCase(String.valueOf(raw).trim())) {
            return true;
        }
        return "sequential".equalsIgnoreCase(String.valueOf(debug.getOrDefault("langgraph.fallback", "")).trim());
    }

    private static double promotionScore(Map<String, Object> graphDebug,
                                         boolean fallbackTriggered,
                                         double answerDiffScore,
                                         int graphCount,
                                         int legacyCount) {
        double score = asDouble(graphDebug == null ? null : graphDebug.get("langgraph.control.transitionScore"), 1.0d);
        if (fallbackTriggered) {
            score -= 0.60d;
        }
        if (graphCount <= 0) {
            score -= 0.25d;
        }
        if (legacyCount > 0 && graphCount <= 0) {
            score -= 0.15d;
        }
        score -= Math.max(0.0d, answerDiffScore) * 0.20d;
        return Math.round(clamp01(score) * 1000.0d) / 1000.0d;
    }

    private static List<String> promotionBlockers(Map<String, Object> graphDebug,
                                                  boolean fallbackTriggered,
                                                  double answerDiffScore,
                                                  int graphCount,
                                                  int legacyCount) {
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (fallbackTriggered) {
            blockers.add("invoke_fallback");
        }
        if (graphCount <= 0) {
            blockers.add("zero_graph_results");
        }
        if (legacyCount > 0 && graphCount <= 0) {
            blockers.add("legacy_only_results");
        }
        if (answerDiffScore >= 0.65d) {
            blockers.add("high_answer_diff");
        }
        String failureReason = graphDebug == null ? "" : String.valueOf(graphDebug.getOrDefault("langgraph.failureReason", "")).trim();
        if (!failureReason.isBlank()) {
            blockers.add("failure_reason");
        }
        if (graphDebug != null && graphDebug.containsKey("langgraph.node.repair")) {
            blockers.add("repair_triggered");
        }
        Object rawBlockers = graphDebug == null ? null : graphDebug.get("langgraph.control.promotionBlockers");
        if (rawBlockers instanceof Iterable<?> iterable) {
            for (Object raw : iterable) {
                String value = raw == null ? "" : String.valueOf(raw).trim();
                if (!value.isBlank()) {
                    blockers.add(value.length() <= 80 ? value : value.substring(0, 80));
                }
            }
        }
        double score = promotionScore(graphDebug, fallbackTriggered, answerDiffScore, graphCount, legacyCount);
        if (score < 0.70d) {
            blockers.add("low_promotion_score");
        }
        return List.copyOf(new ArrayList<>(blockers));
    }

    private static double asDouble(Object raw, double fallback) {
        if (raw instanceof Number n) {
            double value = n.doubleValue();
            if (!Double.isFinite(value)) {
                traceAsDoubleFallback();
                return fallback;
            }
            return value;
        }
        if (raw == null) {
            return fallback;
        }
        try { double value = Double.parseDouble(String.valueOf(raw).trim()); if (!Double.isFinite(value)) { throw new NumberFormatException("non-finite"); } return value;
        } catch (NumberFormatException ignored) {
            traceAsDoubleFallback();
            return fallback;
        }
    }

    private static void traceAsDoubleFallback() {
        TraceStore.put("langgraph.facade.suppressed.asDouble", true);
        TraceStore.put("langgraph.facade.suppressed.asDouble.errorType", "invalid_number");
        TraceStore.put("langgraph.facade.suppressed.stage", "asDouble");
        TraceStore.put("langgraph.facade.suppressed.errorType", "invalid_number");
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static double answerDiffScore(QueryResponse legacyResponse, QueryResponse graphResponse) {
        Set<String> legacy = evidenceSignatures(legacyResponse);
        Set<String> graph = evidenceSignatures(graphResponse);
        if (legacy.isEmpty() && graph.isEmpty()) {
            return 0.0d;
        }
        Set<String> union = new LinkedHashSet<>(legacy);
        union.addAll(graph);
        if (union.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new LinkedHashSet<>(legacy);
        intersection.retainAll(graph);
        double similarity = (double) intersection.size() / (double) union.size();
        return 1.0d - similarity;
    }

    private static Set<String> evidenceSignatures(QueryResponse response) {
        Set<String> signatures = new LinkedHashSet<>();
        if (response == null || response.results == null) {
            return signatures;
        }
        int limit = Math.min(5, response.results.size());
        for (int i = 0; i < limit; i++) {
            UnifiedRagOrchestrator.Doc doc = response.results.get(i);
            String signature = evidenceSignature(doc);
            if (!signature.isBlank()) {
                signatures.add(signature);
            }
        }
        return signatures;
    }

    private static String evidenceSignature(UnifiedRagOrchestrator.Doc doc) {
        if (doc == null) {
            return "";
        }
        String id = normalize(doc.id);
        if (!id.isBlank()) {
            return "id:" + id;
        }
        String source = normalize(doc.source);
        String title = normalize(doc.title);
        String snippet = normalize(doc.snippet);
        String joined = (source + "|" + title + "|" + snippet).trim();
        return "|".equals(joined) || "||".equals(joined) ? "" : joined;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void copyShadowDebug(Map<String, Object> target,
                                        Map<String, Object> source,
                                        String sourceKey,
                                        String targetKey) {
        if (source.containsKey(sourceKey)) {
            target.put(targetKey, source.get(sourceKey));
        }
    }

    private static Map<String, Object> ensureDebug(QueryResponse response) {
        if (response == null) {
            return new LinkedHashMap<>();
        }
        if (response.debug == null) {
            response.debug = new LinkedHashMap<>();
        }
        return response.debug;
    }

    private static final class FixedObjectProvider<T> implements ObjectProvider<T> {
        private final T value;

        private FixedObjectProvider(T value) {
            this.value = value;
        }

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
    }
}

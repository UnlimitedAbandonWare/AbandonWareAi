package com.example.lms.service.rag;

import com.example.lms.domain.enums.ExecutionMode;
import com.example.lms.search.TraceStore;
import com.example.lms.search.probe.EvidenceSignals;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Existing local BFS limit plus the request-owned query / actual HTTP envelope. */
public final class SelfAskSearchBudget {
    public static final String REQUEST_TRACE_KEY = "selfask.requestBudget.internal";
    private final AtomicInteger left;
    private final ExecutionMode requested;
    private final Set<String> queryHashes = new HashSet<>();
    private final Map<String, String> queryAliases = new HashMap<>();
    private int httpAttempts;
    private boolean expansionAllowed;
    private String reason = "base-retrieval";

    SelfAskSearchBudget(int max) { left = new AtomicInteger(Math.max(0, max)); requested = null; }
    private SelfAskSearchBudget(ExecutionMode mode) { left = new AtomicInteger(0); requested = mode; }
    boolean tryConsume() { return left.getAndUpdate(n -> Math.max(0, n - 1)) > 0; }
    int remaining() { return Math.max(0, left.get()); }

    public static SelfAskSearchBudget beginRequest(ExecutionMode mode) {
        var budget = new SelfAskSearchBudget(mode == null ? ExecutionMode.AUTO : mode);
        restore(budget);
        return budget;
    }
    public static SelfAskSearchBudget current() { return from(TraceStore.context()); }
    private static SelfAskSearchBudget from(Map<String, Object> context) {
        Object value = context == null ? null : context.get(REQUEST_TRACE_KEY);
        return value instanceof SelfAskSearchBudget budget && budget.requested != null ? budget : null;
    }
    public static void restore(SelfAskSearchBudget budget) {
        TraceStore.putInternal(REQUEST_TRACE_KEY, budget);
        budget.publish(TraceStore.context());
    }
    public ExecutionMode requested() { return requested; }
    public synchronized boolean expansionAllowed() { return expansionAllowed; }

    public synchronized boolean allowExpansion(String cause) {
        if (requested == ExecutionMode.STRIKE) { reason = "user-strike"; return false; }
        if (!expansionAllowed) {
            expansionAllowed = true;
            reason = "user-self-ask".equals(cause) ? cause : "evidence-gap";
        }
        publish(TraceStore.context());
        return true;
    }
    public synchronized void skip(String cause) {
        if (!expansionAllowed) reason = switch (cause) {
            case "user-strike", "safety-gate", "search-off", "deadline-or-cancel",
                 "evidence-sufficient", "simple-query", "global-disabled", "cheap-search-mode" -> cause;
            default -> "safety-gate";
        };
        publish(TraceStore.context());
    }
    public boolean tryQuery(String query) { return admitQuery(query, TraceStore.context()); }
    private synchronized boolean admitQuery(String query, Map<String, Object> context) {
        if (query == null || query.isBlank()) return false;
        String hash = queryAliases.getOrDefault(queryHash(query), queryHash(query));
        if (queryHashes.contains(hash)) return true;
        int limit = expansionAllowed && requested != ExecutionMode.STRIKE ? 3 : 1;
        if (queryHashes.size() >= limit) return false;
        queryHashes.add(hash);
        publish(context);
        return true;
    }
    private static String queryHash(String query) {
        return SafeRedactor.hashValue(query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
    }
    /** Adapter normalization is a representation of one logical query, not an expansion. */
    public static boolean tryQueryAlias(String logical, String normalized) {
        var budget = current();
        if (budget == null) return true;
        synchronized (budget) {
            if (logical == null || normalized == null || normalized.isBlank()
                    || !budget.admitQuery(logical, TraceStore.context())) return false;
            String logicalHash = budget.queryAliases.getOrDefault(queryHash(logical), queryHash(logical));
            budget.queryAliases.putIfAbsent(queryHash(normalized), logicalHash);
            return true;
        }
    }
    public static boolean tryReserveHttp(Map<String, Object> context, String query) {
        var budget = from(context);
        if (budget == null) return true; // Non-chat callers retain their existing contract.
        synchronized (budget) {
            if (Thread.currentThread().isInterrupted() || budget.httpAttempts >= 6 || !budget.admitQuery(query, context)) {
                budget.reason = Thread.currentThread().isInterrupted() ? "deadline-or-cancel" : "search-budget";
                budget.publish(context);
                return false;
            }
            budget.httpAttempts++;
            budget.publish(context);
            return true;
        }
    }
    private synchronized void publish(Map<String, Object> context) {
        context.put("executionMode.requested", requested.name());
        context.put("executionMode.effective", requested == ExecutionMode.STRIKE ? "STRIKE" : queryHashes.size() > 1 ? "SELF_ASK" : "AUTO");
        context.put("executionMode.reason", reason);
        context.put("executionMode.queryCount", queryHashes.size());
        context.put("executionMode.httpAttempts", httpAttempts);
        context.put("executionMode.expanded", queryHashes.size() > 1);
        context.put("executionMode.expansionAllowed", expansionAllowed);
    }

    /** Relation intent and existing citation/conflict signals, never input length alone. */
    public static boolean needsExpansion(String query, List<Content> evidence) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        boolean relation = text.matches("(?s).*(비교|차이|관계|역할|구분|compare|difference|relationship|\\bvs\\b).*");
        boolean factual = relation || text.matches("(?s).*(근거|출처|공식|최신|누가|누구|언제|왜|evidence|source|official|latest|\\bwho\\b|\\bwhen\\b|\\bwhy\\b).*");
        if (!factual) return false;
        var signals = EvidenceSignals.computeLane("direct", evidence, null, null, 0);
        boolean supported = signals.distinctCitationCount() >= (relation ? 2 : 1)
                && signals.strongCitationRate() >= 0.5d && signals.confidence() >= 0.5d
                && signals.contradictionRate() <= 0.25d;
        return !supported;
    }
}

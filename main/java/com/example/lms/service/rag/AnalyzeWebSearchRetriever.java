package com.example.lms.service.rag;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.search.TraceStore;
import com.example.lms.search.policy.SearchPolicyDecision;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.routing.plan.RoutingPlanService;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.analysis.Analyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AnalyzeWebSearchRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeWebSearchRetriever.class);

    private final WebSearchProvider webSearchProvider;
    private final QueryContextPreprocessor preprocessor;
    private final RoutingPlanService routingPlanService;
    private final SearchPolicyEngine searchPolicyEngine;
    @SuppressWarnings("unused")
    private final Analyzer analyzer;
    @SuppressWarnings("unused")
    private final ExecutorService searchIoExecutor;

    private volatile int timeoutMs = 1800;
    private volatile int webTopK = 10;

    @Value("${gpt-search.analyze.fanout.enabled:false}")
    private volatile boolean parallelFanoutEnabled;
    @Value("${gpt-search.analyze.fanout.workers:4}")
    private volatile int fanoutWorkers = 4;
    @Value("${gpt-search.analyze.fanout.queue-capacity:32}")
    private volatile int fanoutQueueCapacity = 32;
    private volatile ExecutorService fanoutExecutor;
    private static final AtomicInteger FANOUT_THREAD_SEQ = new AtomicInteger();

    public AnalyzeWebSearchRetriever(
            Analyzer analyzer,
            WebSearchProvider webSearchProvider,
            @Qualifier("guardrailQueryPreprocessor") QueryContextPreprocessor preprocessor,
            RoutingPlanService routingPlanService,
            SearchPolicyEngine searchPolicyEngine,
            @Qualifier("searchIoExecutor") ExecutorService searchIoExecutor) {
        this.analyzer = analyzer;
        this.webSearchProvider = webSearchProvider;
        this.preprocessor = preprocessor;
        this.routingPlanService = routingPlanService;
        this.searchPolicyEngine = searchPolicyEngine;
        this.searchIoExecutor = searchIoExecutor;
    }

    public void setTimeoutMs(int ms) {
        this.timeoutMs = ms;
    }

    public void setWebTopK(int k) {
        this.webTopK = k;
    }

    public void setParallelFanoutEnabled(boolean enabled) {
        this.parallelFanoutEnabled = enabled;
    }

    public void setFanoutWorkers(int workers) {
        this.fanoutWorkers = workers;
    }

    public void setFanoutQueueCapacity(int capacity) {
        this.fanoutQueueCapacity = capacity;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String originalQuery = query == null || query.text() == null ? "" : query.text().trim();
        if (originalQuery.isBlank()) {
            trace("provider-empty", 0, 0);
            return Collections.emptyList();
        }

        Map<String, Object> metadata = QueryUtils.metadata(query);
        if (webSearchProvider == null) {
            traceMissingDependency("web_provider_missing", originalQuery);
            return Collections.emptyList();
        }
        long preparationStartedNs = System.nanoTime();
        if (preprocessor != null) {
            try {
                originalQuery = preprocessor.enrich(originalQuery, Map.of("purpose", "WEB_SEARCH"));
            } catch (RuntimeException e) {
                traceFailure("preprocessor", originalQuery, e);
            }
        }

        int requestedTopK = Math.max(1, metaInt(metadata, "webTopK", webTopK));
        SearchPolicyDecision decision = null;
        if (searchPolicyEngine != null) {
            try {
                decision = searchPolicyEngine.decide(originalQuery, metadata);
                requestedTopK = searchPolicyEngine.tuneTopK(requestedTopK, decision);
            } catch (RuntimeException e) {
                traceFailure("policy", originalQuery, e);
            }
        }

        boolean alreadyPlanned = Boolean.TRUE.equals(metadata.get("webQueryAlreadyPlanned"));
        // Keep guardrail preprocessing and search policy, but do not repeat expensive
        // LLM query expansion for the real-time caller's already-refined query.
        List<String> planned = alreadyPlanned
                ? List.of(originalQuery)
                : planQueries(originalQuery, decision);
        TraceStore.put("web.analyze.queryPlanningSkipped", alreadyPlanned);
        TraceStore.put("web.analyze.preparationMs", Math.max(0L, (System.nanoTime() - preparationStartedNs) / 1_000_000));
        List<Content> out = new ArrayList<>();
        boolean partialSearchFailed = false;
        if (parallelFanoutEnabled && planned.size() > 1) {
            FanoutOutcome fanout = retrieveFanout(planned, requestedTopK, originalQuery);
            out.addAll(fanout.contents);
            partialSearchFailed = fanout.partialFailed;
        } else {
            for (String plannedQuery : planned) {
                try {
                    List<String> raw = webSearchProvider.search(plannedQuery, requestedTopK);
                    if (raw != null) {
                        raw.stream()
                                .filter(Objects::nonNull)
                                .filter(s -> !s.isBlank())
                                .map(Content::from)
                                .forEach(out::add);
                    }
                } catch (RuntimeException | AssertionError e) {
                    partialSearchFailed = true;
                    traceFailure("provider", plannedQuery, e);
                    tracePartialSearchFailure(originalQuery, e);
                    log.debug("[Analyze] fail-soft stage={} errorType={}",
                            "partialSearch.interrupted",
                            errorType(e));
                    log.warn("[AWX][search][analyze] provider failed reason={} errorType={} queryHash12={} queryLength={}",
                            "provider-error",
                            errorType(e),
                            SafeRedactor.hash12(plannedQuery),
                            plannedQuery == null ? 0 : plannedQuery.length());
                }
            }
        }
        if (out.isEmpty() && partialSearchFailed && !planned.contains(originalQuery)) {
            try {
                List<String> raw = webSearchProvider.search(originalQuery, requestedTopK);
                if (raw != null) {
                    raw.stream()
                            .filter(Objects::nonNull)
                            .filter(s -> !s.isBlank())
                            .map(Content::from)
                            .forEach(out::add);
                }
            } catch (RuntimeException | AssertionError e) {
                traceFallbackSearchFailure(originalQuery, e);
                log.debug("[Analyze] fail-soft stage={} errorType={}",
                        "cancelFuture",
                        errorType(e));
            }
        }

        trace(out.isEmpty() ? "provider-empty" : "ok", requestedTopK, out.size());
        return out;
    }

    /**
     * Flag-gated bounded fanout for planned queries (gpt-search.analyze.fanout.*).
     * Runs provider calls on a small dedicated pool — never on searchIoExecutor,
     * which provider hedging already occupies — while the caller collects in
     * completion order bounded by the shared TimeBudget. Merge stays
     * deterministic in planned order so evidence is identical to the serial
     * loop whenever every task finishes.
     */
    private FanoutOutcome retrieveFanout(List<String> planned, int requestedTopK, String originalQuery) {
        TimeBudget budget = TimeBudgetContext.get();
        ExecutorCompletionService<PlannedSearchResult> ecs =
                new ExecutorCompletionService<>(fanoutExecutor());
        List<List<String>> slots = new ArrayList<>(Collections.nCopies(planned.size(), null));
        int submitted = 0;
        int completed = 0;
        int skippedBeforeStart = 0;
        int inlineSerial = 0;
        boolean partialFailed = false;
        boolean deadlineReached = false;
        boolean interrupted = false;

        for (int i = 0; i < planned.size(); i++) {
            if (budget != null && budget.expired()) {
                skippedBeforeStart += planned.size() - i;
                break;
            }
            final int index = i;
            final String plannedQuery = planned.get(i);
            Callable<PlannedSearchResult> work = ContextPropagation.wrapCallable(() -> {
                if (budget != null && budget.expired()) {
                    return PlannedSearchResult.skipped(index);
                }
                try {
                    return PlannedSearchResult.ok(index, webSearchProvider.search(plannedQuery, requestedTopK));
                } catch (RuntimeException | AssertionError e) {
                    return PlannedSearchResult.failed(index, e);
                }
            });
            try {
                ecs.submit(work);
                submitted++;
            } catch (RejectedExecutionException saturated) {
                // Bounded pool saturated: the caller thread is the waiter anyway,
                // so finish the remaining queries with the same serial semantics.
                inlineSerial += planned.size() - i;
                for (int j = i; j < planned.size(); j++) {
                    if (budget != null && budget.expired()) {
                        skippedBeforeStart += planned.size() - j;
                        break;
                    }
                    try {
                        slots.set(j, webSearchProvider.search(planned.get(j), requestedTopK));
                    } catch (RuntimeException | AssertionError e) {
                        partialFailed = true;
                        traceFailure("provider", planned.get(j), e);
                        tracePartialSearchFailure(originalQuery, e);
                        log.warn("[AWX][search][analyze] provider failed reason={} errorType={} queryHash12={} queryLength={}",
                                "provider-error",
                                errorType(e),
                                SafeRedactor.hash12(planned.get(j)),
                                planned.get(j) == null ? 0 : planned.get(j).length());
                    }
                }
                break;
            }
        }

        for (int i = 0; i < submitted; i++) {
            long waitMs = budget == null ? Long.MAX_VALUE : budget.remainingMillis();
            Future<PlannedSearchResult> done;
            try {
                done = waitMs <= 0 ? ecs.poll() : ecs.poll(waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                interrupted = true;
                break;
            }
            if (done == null) {
                deadlineReached = true;
                break;
            }
            PlannedSearchResult result;
            try {
                result = done.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                interrupted = true;
                break;
            } catch (java.util.concurrent.ExecutionException ee) {
                result = PlannedSearchResult.failed(-1, ee.getCause() == null ? ee : ee.getCause());
            }
            completed++;
            if (result.skipped) {
                skippedBeforeStart++;
            } else if (result.error != null) {
                partialFailed = true;
                String failedQuery = result.index >= 0 && result.index < planned.size()
                        ? planned.get(result.index) : null;
                traceFailure("provider", failedQuery, result.error);
                tracePartialSearchFailure(originalQuery, result.error);
                log.warn("[AWX][search][analyze] provider failed reason={} errorType={} queryHash12={} queryLength={}",
                        "provider-error",
                        errorType(result.error),
                        SafeRedactor.hash12(failedQuery),
                        failedQuery == null ? 0 : failedQuery.length());
            } else if (result.index >= 0 && result.index < slots.size()) {
                slots.set(result.index, result.raw);
            }
        }

        List<Content> contents = new ArrayList<>();
        for (List<String> raw : slots) {
            if (raw == null) {
                continue;
            }
            raw.stream()
                    .filter(Objects::nonNull)
                    .filter(s -> !s.isBlank())
                    .map(Content::from)
                    .forEach(contents::add);
        }

        TraceStore.put("web.analyze.fanout.enabled", true);
        TraceStore.put("web.analyze.fanout.submitted", submitted);
        TraceStore.put("web.analyze.fanout.completed", completed);
        TraceStore.put("web.analyze.fanout.skippedBeforeStart", skippedBeforeStart);
        TraceStore.put("web.analyze.fanout.rejectedInlineSerial", inlineSerial);
        TraceStore.put("web.analyze.fanout.deadlineReached", deadlineReached);
        TraceStore.put("web.analyze.fanout.interrupted", interrupted);
        TraceStore.put("web.analyze.fanout.workersUnfinishedAtReturn", submitted - completed);
        return new FanoutOutcome(contents, partialFailed);
    }

    private ExecutorService fanoutExecutor() {
        ExecutorService existing = fanoutExecutor;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (fanoutExecutor == null) {
                int workers = Math.max(1, Math.min(8, fanoutWorkers));
                int queue = Math.max(8, fanoutQueueCapacity);
                fanoutExecutor = new ThreadPoolExecutor(
                        workers, workers,
                        0L, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(queue),
                        runnable -> {
                            Thread t = new Thread(runnable,
                                    "awx-analyze-web-fanout-" + FANOUT_THREAD_SEQ.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        });
            }
            return fanoutExecutor;
        }
    }

    @PreDestroy
    void shutdownFanout() {
        ExecutorService executor = fanoutExecutor;
        if (executor != null) {
            executor.shutdown();
        }
    }

    private static final class PlannedSearchResult {
        final int index;
        final List<String> raw;
        final Throwable error;
        final boolean skipped;

        private PlannedSearchResult(int index, List<String> raw, Throwable error, boolean skipped) {
            this.index = index;
            this.raw = raw;
            this.error = error;
            this.skipped = skipped;
        }

        static PlannedSearchResult ok(int index, List<String> raw) {
            return new PlannedSearchResult(index, raw, null, false);
        }

        static PlannedSearchResult failed(int index, Throwable error) {
            return new PlannedSearchResult(index, null, error, false);
        }

        static PlannedSearchResult skipped(int index) {
            return new PlannedSearchResult(index, null, null, true);
        }
    }

    private static final class FanoutOutcome {
        final List<Content> contents;
        final boolean partialFailed;

        FanoutOutcome(List<Content> contents, boolean partialFailed) {
            this.contents = contents;
            this.partialFailed = partialFailed;
        }
    }

    private List<String> planQueries(String originalQuery, SearchPolicyDecision decision) {
        if (routingPlanService == null) {
            return List.of(originalQuery);
        }
        try {
            List<String> base = routingPlanService.plan(originalQuery, null, Math.max(1, Math.min(8, webTopK)));
            List<String> planned = decision == null
                    ? base
                    : searchPolicyEngine == null ? base : searchPolicyEngine.apply(base, originalQuery, decision);
            if (planned != null && !planned.isEmpty()) {
                return planned;
            }
        } catch (RuntimeException e) {
            traceFailure("planner", originalQuery, e);
        }
        return List.of(originalQuery);
    }

    private static int metaInt(Map<String, Object> metadata, String key, int fallback) {
        Object value = metadata == null ? null : metadata.get(key);
        if (value instanceof Number n) {
            if (value instanceof Double d && !Double.isFinite(d)) {
                return fallback;
            }
            if (value instanceof Float f && !Float.isFinite(f)) {
                return fallback;
            }
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                s = s.trim();
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                log.debug("[AnalyzeWebSearchRetriever] fail-soft stage={} errorType={}", "metaInt.parse", "invalid_number");
                return fallback;
            }
        }
        return fallback;
    }

    private void trace(String reason, int requestedTopK, int returnedCount) {
        TraceStore.put("web.analyze.skipped.reason", reason);
        TraceStore.put("web.analyze.requestedCount", requestedTopK);
        TraceStore.put("web.analyze.returnedCount", returnedCount);
        TraceStore.put("web.analyze.timeoutMs", timeoutMs);
    }

    private void traceMissingDependency(String reason, String query) {
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "dependency_missing");
        trace(safeReason, 0, 0);
        TraceStore.put("web.analyze.providerDisabled", true);
        TraceStore.put("web.analyze.disabledReason", safeReason);
        TraceStore.put("web.analyze.queryHash12", SafeRedactor.hash12(query));
        TraceStore.put("web.analyze.queryLength", query == null ? 0 : query.length());
    }

    private static void traceFailure(String stage, String query, Throwable e) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("web.analyze." + safeStage + ".errorType",
                errorType(e));
        TraceStore.put("web.analyze." + safeStage + ".queryHash12", SafeRedactor.hash12(query));
        TraceStore.put("web.analyze." + safeStage + ".queryLength", query == null ? 0 : query.length());
    }

    private static void tracePartialSearchFailure(String query, Throwable e) {
        TraceStore.put("web.analyze.partialSearch.failed", true);
        TraceStore.put("web.analyze.partialSearch.failureReason", "exception");
        TraceStore.put("web.analyze.partialSearch.errorType", errorType(e));
        TraceStore.put("web.analyze.partialSearch.queryHash12", SafeRedactor.hash12(query));
        TraceStore.put("web.analyze.partialSearch.queryLength", query == null ? 0 : query.length());
    }

    private static void traceFallbackSearchFailure(String query, Throwable e) {
        TraceStore.put("web.analyze.fallbackSearch.failed", true);
        TraceStore.put("web.analyze.fallbackSearch.failureReason", "exception");
        TraceStore.put("web.analyze.fallbackSearch.errorType", errorType(e));
        TraceStore.put("web.analyze.fallbackSearch.queryHash12", SafeRedactor.hash12(query));
        TraceStore.put("web.analyze.fallbackSearch.queryLength", query == null ? 0 : query.length());
    }

    private static String errorType(Throwable e) {
        return SafeRedactor.traceLabelOrFallback(e == null ? null : e.getClass().getSimpleName(), "unknown");
    }
}

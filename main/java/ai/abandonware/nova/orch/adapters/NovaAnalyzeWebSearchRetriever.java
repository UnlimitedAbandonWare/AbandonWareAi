package ai.abandonware.nova.orch.adapters;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.search.policy.SearchPolicyDecision;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.AnalyzeSearchTimeoutTrace;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.routing.plan.RoutingPlanService;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.apache.lucene.analysis.Analyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Nova overlay implementation of {@link AnalyzeWebSearchRetriever}.
 *
 * <p>Fixes:</p>
 * <ul>
 *   <li>Only attempts Brave JSON flattening when the snippet actually looks like Brave JSON.</li>
 *   <li>Better URL extraction for de-duplication when snippets are HTML or stage-tagged.</li>
 * </ul>
 */
public class NovaAnalyzeWebSearchRetriever extends AnalyzeWebSearchRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(NovaAnalyzeWebSearchRetriever.class);
    private static final int DEFAULT_SEARCH_ADMISSION_LIMIT = 64;

    private static final Pattern HREF_DQ = Pattern.compile("href=\\\"(https?://[^\\\"\\s>]+)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_SQ = Pattern.compile("href='(https?://[^'\\s>]+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_URL = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    private final Analyzer analyzer; // signature compatibility
    private final WebSearchProvider webSearchProvider;
    private final QueryContextPreprocessor preprocessor;
    private final RoutingPlanService routingPlanService;
    private final SearchPolicyEngine searchPolicyEngine;
    private final ExecutorService searchIoExecutor;
    private final ObjectMapper objectMapper;
    private final Object searchAdmissionConfigLock = new Object();
    private final AtomicInteger activeSearchLeases = new AtomicInteger();
    private final AtomicInteger waitingSearchAdmissions = new AtomicInteger();
    private volatile Semaphore searchAdmissionPermits =
            new Semaphore(DEFAULT_SEARCH_ADMISSION_LIMIT, true);

    private volatile int timeoutMs = 1800;
    private volatile int webTopK = 10;

    public NovaAnalyzeWebSearchRetriever(
            Analyzer analyzer,
            WebSearchProvider webSearchProvider,
            @Qualifier("guardrailQueryPreprocessor") QueryContextPreprocessor preprocessor,
            RoutingPlanService routingPlanService,
            SearchPolicyEngine searchPolicyEngine,
            @Qualifier("searchIoExecutor") ExecutorService searchIoExecutor,
            ObjectMapper objectMapper) {
        super(analyzer, webSearchProvider, preprocessor, routingPlanService, searchPolicyEngine, searchIoExecutor);
        this.analyzer = analyzer;
        this.webSearchProvider = Objects.requireNonNull(webSearchProvider);
        this.preprocessor = Objects.requireNonNull(preprocessor);
        this.routingPlanService = Objects.requireNonNull(routingPlanService);
        this.searchPolicyEngine = Objects.requireNonNull(searchPolicyEngine);
        this.searchIoExecutor = Objects.requireNonNull(searchIoExecutor);
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    @Override
    public void setTimeoutMs(int ms) {
        super.setTimeoutMs(ms);
        this.timeoutMs = ms;
    }

    @Override
    public void setWebTopK(int k) {
        super.setWebTopK(k);
        this.webTopK = k;
    }

    @org.springframework.beans.factory.annotation.Value("${search.executor.io.max-size:64}")
    void configureSearchAdmissionLimit(int requestedLimit) {
        resetSearchAdmissionLimit(requestedLimit);
    }

    void setSearchAdmissionLimitForTest(int requestedLimit) {
        resetSearchAdmissionLimit(requestedLimit);
    }

    @Override
    public List<Content> retrieve(Query query) {
        String originalQuery = (query != null && query.text() != null) ? query.text().trim() : "";
        {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("purpose", "WEB_SEARCH");
            originalQuery = preprocessor.enrich(originalQuery, meta);
        }
        if (originalQuery == null || originalQuery.isBlank()) {
            return Collections.emptyList();
        }

        final long operationTimeoutMs = effectiveOperationTimeoutMs();
        if (operationTimeoutMs <= 0L) {
            traceDeadlineExceeded(originalQuery, "before_policy", operationTimeoutMs);
            return Collections.emptyList();
        }
        final long deadlineNs = deadlineAfterMillis(operationTimeoutMs);

        Map<String, Object> qMeta = com.example.lms.service.rag.QueryUtils.metadata(query);

        SearchPolicyDecision spDecision = null;
        try {
            spDecision = searchPolicyEngine.decide(originalQuery, qMeta);
        } catch (Exception e) {
            traceSearchPolicyFailure(originalQuery, "decide", e);
            log.debug("[Analyze][nova] search policy failed failureReason={} errorType={} queryHash={} queryLength={}",
                    analyzeFailureReason(e),
                    analyzeErrorType(e),
                    SafeRedactor.hashValue(originalQuery),
                    originalQuery.length());
        }

        boolean explicitWebTopK = qMeta.containsKey("webTopK");
        int calculatedTopK = metaInt(qMeta, "webTopK", this.webTopK);
        if (!explicitWebTopK && spDecision != null) {
            calculatedTopK = safeTuneTopK(calculatedTopK, spDecision, originalQuery);
        }
        final int reqTopK = calculatedTopK;

        int plannerMax = 8;
        if (spDecision != null) {
            plannerMax = safeTunePlannerMax(plannerMax, spDecision, originalQuery);
        }

        List<String> basePlan = Collections.emptyList();
        boolean routingPlanFailed = false;
        CompletionService<List<String>> planCompletions =
                new ExecutorCompletionService<>(searchIoExecutor);
        final int boundedPlannerMax = plannerMax;
        final String plannedOriginalQuery = originalQuery;
        SubmittedWork<List<String>> planWork = submitLeased(
                planCompletions,
                ContextPropagation.wrapCallable(
                        () -> routingPlanService.plan(plannedOriginalQuery, null, boundedPlannerMax)),
                "routing_plan",
                deadlineNs,
                operationTimeoutMs,
                originalQuery);
        if (planWork == null) {
            return Collections.emptyList();
        }
        try {
            Future<List<String>> completedPlan = pollUntilDeadline(planCompletions, deadlineNs);
            if (completedPlan == null) {
                CancelStats stats = cancelOutstanding(List.of(planWork));
                recordWorkerOutcome(stats);
                AnalyzeSearchTimeoutTrace.recordCancelSuppressed(
                        "NovaAnalyze",
                        operationTimeoutMs,
                        stats.attempted(),
                        stats.succeeded(),
                        originalQuery);
                traceDeadlineExceeded(originalQuery, "routing_plan", operationTimeoutMs);
                return Collections.emptyList();
            }
            try {
                List<String> planned = completedPlan.get();
                basePlan = planned == null ? Collections.emptyList() : planned;
            } catch (ExecutionException | CancellationException planFailure) {
                routingPlanFailed = true;
                traceRoutingPlanFailure(originalQuery, planFailure);
            }
        } catch (InterruptedException interrupted) {
            CancelStats stats = cancelOutstanding(List.of(planWork));
            recordWorkerOutcome(stats);
            traceInterruptedPoll(originalQuery, interrupted);
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }

        List<String> queries = routingPlanFailed
                ? Collections.emptyList()
                : (spDecision != null)
                        ? safeApplyPolicy(basePlan, originalQuery, spDecision)
                        : basePlan;

        if (!routingPlanFailed && (queries == null || queries.isEmpty())) {
            queries = List.of(originalQuery);
        }

        List<String> merged = new ArrayList<>();
        if (!routingPlanFailed) {
            CompletionService<List<String>> completions =
                    new ExecutorCompletionService<>(searchIoExecutor);
            List<SubmittedWork<List<String>>> submitted = new ArrayList<>();
            for (String q : queries) {
                if (q == null || q.isBlank()) {
                    continue;
                }
                SubmittedWork<List<String>> work = submitLeased(
                        completions,
                        ContextPropagation.wrapCallable(() -> {
                            try {
                                return webSearchProvider.search(q, reqTopK);
                            } catch (Exception e) {
                                log.debug("[Analyze][nova] web search failed failureReason={} errorType={} queryHash={} queryLength={}",
                                        analyzeFailureReason(e),
                                        analyzeErrorType(e),
                                        SafeRedactor.hashValue(q),
                                        q.length());
                                tracePlannedSearchFailure(q, e);
                                return Collections.emptyList();
                            }
                        }),
                        "planned_search",
                        deadlineNs,
                        operationTimeoutMs,
                        originalQuery);
                if (work != null) {
                    submitted.add(work);
                }
            }

            if (submitted.isEmpty() && queries != null && !queries.isEmpty()) {
                return Collections.emptyList();
            }

            int remaining = submitted.size();
            while (remaining > 0 && remainingMillis(deadlineNs) > 0L) {
                try {
                    Future<List<String>> completed = pollUntilDeadline(completions, deadlineNs);
                    if (completed == null) {
                        break;
                    }
                    remaining--;
                    if (completed.isCancelled()) {
                        continue;
                    }
                    try {
                        List<String> part = completed.get();
                        if (part != null && !part.isEmpty()) {
                            merged.addAll(part);
                        }
                    } catch (Exception e) {
                        log.debug("[Analyze][nova] partial search failure failureReason={} errorType={} queryHash={} queryLength={}",
                                analyzeFailureReason(e),
                                analyzeErrorType(e),
                                SafeRedactor.hashValue(originalQuery),
                                originalQuery.length());
                        tracePartialSearchFailure(originalQuery, e);
                    }
                } catch (InterruptedException interrupted) {
                    traceInterruptedPoll(originalQuery, interrupted);
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            CancelStats cancelStats = cancelOutstanding(submitted);
            recordWorkerOutcome(cancelStats);
            AnalyzeSearchTimeoutTrace.recordCancelSuppressed(
                    "NovaAnalyze",
                    operationTimeoutMs,
                    cancelStats.attempted(),
                    cancelStats.succeeded(),
                    originalQuery);
        }

        List<String> flattened = new ArrayList<>();
        for (String raw : merged) {
            Optional<List<String>> maybeJson = tryFlattenBraveJson(raw, reqTopK);
            if (maybeJson.isPresent()) {
                flattened.addAll(maybeJson.get());
            } else {
                flattened.add(raw);
            }
        }

        LinkedHashMap<String, String> dedup = new LinkedHashMap<>();
        for (String snippet : flattened) {
            if (snippet == null || snippet.isBlank()) {
                continue;
            }
            String url = extractUrl(snippet);
            String key = (url != null && !url.isBlank()) ? url : snippet;
            dedup.putIfAbsent(key, snippet);
        }

        if (dedup.isEmpty()) {
            List<String> fallback = searchOriginalWithinDeadline(
                    originalQuery,
                    reqTopK,
                    deadlineNs,
                    operationTimeoutMs);
            for (String raw : fallback) {
                Optional<List<String>> maybeJson = tryFlattenBraveJson(raw, reqTopK);
                List<String> items = maybeJson.orElse(List.of(raw));
                for (String s : items) {
                    if (s == null || s.isBlank()) {
                        continue;
                    }
                    String url = extractUrl(s);
                    String key = (url != null && !url.isBlank()) ? url : s;
                    dedup.putIfAbsent(key, s);
                }
            }
        }

        return dedup.values().stream()
                .limit(reqTopK)
                .map(Content::from)
                .collect(Collectors.toList());
    }

    private long effectiveOperationTimeoutMs() {
        long configuredMs = Math.max(250L, timeoutMs);
        TimeBudget requestBudget = TimeBudgetContext.get();
        if (requestBudget == null) {
            return configuredMs;
        }
        return Math.max(0L, Math.min(configuredMs, requestBudget.remainingMillis()));
    }

    private static long deadlineAfterMillis(long timeoutMillis) {
        long now = System.nanoTime();
        long timeoutNs = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        return timeoutNs >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeoutNs;
    }

    private static long remainingMillis(long deadlineNs) {
        long remainingNs = deadlineNs - System.nanoTime();
        if (remainingNs <= 0L) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs));
    }

    private static <T> Future<T> pollUntilDeadline(
            CompletionService<T> completions,
            long deadlineNs) throws InterruptedException {
        long remainingMs = remainingMillis(deadlineNs);
        return remainingMs <= 0L ? null : completions.poll(remainingMs, TimeUnit.MILLISECONDS);
    }

    private <T> SubmittedWork<T> submitLeased(
            CompletionService<T> completions,
            Callable<T> task,
            String stage,
            long deadlineNs,
            long operationTimeoutMs,
            String query) {
        if (remainingMillis(deadlineNs) <= 0L) {
            traceDeadlineExceeded(query, stage, operationTimeoutMs);
            return null;
        }
        SearchLease lease;
        try {
            lease = tryAcquireSearchLease(deadlineNs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            traceInterruptedPoll(query, interrupted);
            return null;
        }
        if (lease == null) {
            if (remainingMillis(deadlineNs) <= 0L) {
                traceDeadlineExceeded(query, stage, operationTimeoutMs);
            } else {
                traceAdmissionFailure(query, stage, null);
            }
            return null;
        }
        LeasedTask<T> leasedTask = new LeasedTask<>(task, lease, Thread.currentThread());
        try {
            Future<T> future = completions.submit(leasedTask);
            if (leasedTask.inlineExecutionRejected()) {
                traceAdmissionFailure(query, stage, null);
                return null;
            }
            return new SubmittedWork<>(future, leasedTask);
        } catch (RejectedExecutionException rejected) {
            leasedTask.cancelBeforeStart();
            traceAdmissionFailure(query, stage, rejected);
            return null;
        }
    }

    private List<String> searchOriginalWithinDeadline(
            String originalQuery,
            int reqTopK,
            long deadlineNs,
            long operationTimeoutMs) {
        if (remainingMillis(deadlineNs) <= 0L) {
            traceDeadlineExceeded(originalQuery, "original_fallback", operationTimeoutMs);
            return Collections.emptyList();
        }
        CompletionService<List<String>> completions =
                new ExecutorCompletionService<>(searchIoExecutor);
        SubmittedWork<List<String>> work = submitLeased(
                completions,
                ContextPropagation.wrapCallable(() -> webSearchProvider.search(originalQuery, reqTopK)),
                "original_fallback",
                deadlineNs,
                operationTimeoutMs,
                originalQuery);
        if (work == null) {
            return Collections.emptyList();
        }
        try {
            Future<List<String>> completed = pollUntilDeadline(completions, deadlineNs);
            if (completed == null) {
                CancelStats stats = cancelOutstanding(List.of(work));
                recordWorkerOutcome(stats);
                AnalyzeSearchTimeoutTrace.recordCancelSuppressed(
                        "NovaAnalyze",
                        operationTimeoutMs,
                        stats.attempted(),
                        stats.succeeded(),
                        originalQuery);
                traceDeadlineExceeded(originalQuery, "original_fallback", operationTimeoutMs);
                return Collections.emptyList();
            }
            List<String> result = completed.get();
            return result == null ? Collections.emptyList() : result;
        } catch (InterruptedException interrupted) {
            CancelStats stats = cancelOutstanding(List.of(work));
            recordWorkerOutcome(stats);
            traceInterruptedPoll(originalQuery, interrupted);
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } catch (ExecutionException | CancellationException failure) {
            log.debug("[Analyze][nova] fallback search failed failureReason={} errorType={} queryHash={} queryLength={}",
                    analyzeFailureReason(failure),
                    analyzeErrorType(failure),
                    SafeRedactor.hashValue(originalQuery),
                    originalQuery.length());
            traceFallbackSearchFailure(originalQuery, failure);
            return Collections.emptyList();
        }
    }

    private SearchLease tryAcquireSearchLease(long deadlineNs) throws InterruptedException {
        Semaphore permits;
        synchronized (searchAdmissionConfigLock) {
            permits = searchAdmissionPermits;
            waitingSearchAdmissions.incrementAndGet();
        }
        try {
            long remainingNs = deadlineNs - System.nanoTime();
            if (remainingNs <= 0L
                    || !permits.tryAcquire(remainingNs, TimeUnit.NANOSECONDS)) {
                return null;
            }
            activeSearchLeases.incrementAndGet();
            return new SearchLease(permits, activeSearchLeases);
        } finally {
            waitingSearchAdmissions.decrementAndGet();
        }
    }

    private void resetSearchAdmissionLimit(int requestedLimit) {
        if (requestedLimit < 1 || requestedLimit > 10_000) {
            throw new IllegalArgumentException("invalid_nova_search_admission_limit");
        }
        synchronized (searchAdmissionConfigLock) {
            if (activeSearchLeases.get() != 0 || waitingSearchAdmissions.get() != 0) {
                throw new IllegalStateException("nova_search_admission_in_use");
            }
            searchAdmissionPermits = new Semaphore(requestedLimit, true);
        }
    }

    private static CancelStats cancelOutstanding(List<? extends SubmittedWork<?>> submitted) {
        int attempted = 0;
        int succeeded = 0;
        for (SubmittedWork<?> work : submitted) {
            if (work == null || !work.isOutstanding()) {
                continue;
            }
            attempted++;
            try {
                if (work.cancelWithoutInterrupt()) {
                    succeeded++;
                }
            } catch (RuntimeException cancelFailure) {
                traceCancelFailure("", cancelFailure);
            }
        }
        boolean workerUnfinished = submitted.stream()
                .filter(Objects::nonNull)
                .anyMatch(SubmittedWork::workerUnfinished);
        return new CancelStats(attempted, succeeded, workerUnfinished);
    }

    private static void recordWorkerOutcome(CancelStats stats) {
        if (stats == null || stats.attempted() <= 0) {
            return;
        }
        TraceStore.put("nova.search.callerOutcome", "caller_returned");
        TraceStore.put("nova.search.taskCancelRequested", true);
        TraceStore.put("nova.search.workerTermination",
                stats.workerUnfinished() ? "worker_unfinished" : "worker_terminated");
    }

    private static void traceAdmissionFailure(String query, String stage, Throwable error) {
        TraceStore.put("nova.search.admission.reason", "executor_saturated");
        TraceStore.put("nova.search.admission.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("nova.search.admission.queryHash12", hash12(query));
        TraceStore.put("nova.search.admission.queryLength", query == null ? 0 : query.length());
        if (error != null) {
            TraceStore.put("nova.search.admission.errorType", analyzeErrorType(error));
        }
    }

    private static void traceRoutingPlanFailure(String query, Throwable error) {
        TraceStore.put("nova.search.plan.failureReason", "routing_plan_failed");
        TraceStore.put("nova.search.plan.errorType", analyzeErrorType(error));
        TraceStore.put("nova.search.plan.queryHash12", hash12(query));
        TraceStore.put("nova.search.plan.queryLength", query == null ? 0 : query.length());
    }

    private static void traceDeadlineExceeded(String query, String stage, long timeoutMs) {
        TraceStore.put("nova.search.deadline.reason", "request_deadline_exhausted");
        TraceStore.put("nova.search.deadline.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("nova.search.deadline.timeoutMs", Math.max(0L, timeoutMs));
        TraceStore.put("nova.search.deadline.queryHash12", hash12(query));
        TraceStore.put("nova.search.deadline.queryLength", query == null ? 0 : query.length());
    }

    private enum WorkerState {
        QUEUED,
        RUNNING,
        FINISHED,
        CANCELLED_BEFORE_START
    }

    private static final class SearchLease implements AutoCloseable {
        private final Semaphore permits;
        private final AtomicInteger activeLeases;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SearchLease(Semaphore permits, AtomicInteger activeLeases) {
            this.permits = permits;
            this.activeLeases = activeLeases;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                activeLeases.decrementAndGet();
                permits.release();
            }
        }
    }

    private static final class LeasedTask<T> implements Callable<T> {
        private final Callable<T> delegate;
        private final SearchLease lease;
        private final Thread submittingThread;
        private final AtomicBoolean inlineExecutionRejected = new AtomicBoolean();
        private final AtomicReference<WorkerState> state =
                new AtomicReference<>(WorkerState.QUEUED);

        private LeasedTask(Callable<T> delegate, SearchLease lease, Thread submittingThread) {
            this.delegate = Objects.requireNonNull(delegate);
            this.lease = Objects.requireNonNull(lease);
            this.submittingThread = Objects.requireNonNull(submittingThread);
        }

        @Override
        public T call() throws Exception {
            if (!state.compareAndSet(WorkerState.QUEUED, WorkerState.RUNNING)) {
                throw new CancellationException("nova_search_cancelled_before_start");
            }
            try {
                if (Thread.currentThread() == submittingThread) {
                    inlineExecutionRejected.set(true);
                    throw new RejectedExecutionException("nova_search_inline_execution_rejected");
                }
                return delegate.call();
            } finally {
                state.set(WorkerState.FINISHED);
                lease.close();
            }
        }

        private boolean cancelBeforeStart() {
            if (state.compareAndSet(WorkerState.QUEUED, WorkerState.CANCELLED_BEFORE_START)) {
                lease.close();
                return true;
            }
            return false;
        }

        private boolean isOutstanding() {
            WorkerState current = state.get();
            return current == WorkerState.QUEUED || current == WorkerState.RUNNING;
        }

        private boolean workerUnfinished() {
            return state.get() == WorkerState.RUNNING;
        }

        private boolean inlineExecutionRejected() {
            return inlineExecutionRejected.get();
        }
    }

    private record SubmittedWork<T>(Future<T> future, LeasedTask<T> leasedTask) {
        private boolean cancelWithoutInterrupt() {
            boolean cancelledBeforeStart = leasedTask.cancelBeforeStart();
            return future.cancel(false) || cancelledBeforeStart;
        }

        private boolean isOutstanding() {
            return leasedTask.isOutstanding();
        }

        private boolean workerUnfinished() {
            return leasedTask.workerUnfinished();
        }
    }

    private record CancelStats(int attempted, int succeeded, boolean workerUnfinished) {
    }

    private static int metaInt(Map<String, Object> meta, String key, int defaultValue) {
        if (meta == null || key == null) {
            return defaultValue;
        }
        Object v = meta.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                traceMetaIntParseFallback(key, new NumberFormatException("non-finite"));
                return defaultValue;
            }
            return n.intValue();
        }
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return defaultValue;
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException parseError) {
            traceMetaIntParseFallback(key, parseError);
            return defaultValue;
        }
    }

    private int safeTuneTopK(int baseTopK, SearchPolicyDecision decision, String query) {
        try {
            return searchPolicyEngine.tuneTopK(baseTopK, decision);
        } catch (Exception e) {
            traceSearchPolicyFailure(query, "tuneTopK", e);
            return baseTopK;
        }
    }

    private int safeTunePlannerMax(int basePlannerMax, SearchPolicyDecision decision, String query) {
        try {
            return searchPolicyEngine.tunePlannerMaxQueries(basePlannerMax, decision);
        } catch (Exception e) {
            traceSearchPolicyFailure(query, "tunePlannerMax", e);
            return basePlannerMax;
        }
    }

    private List<String> safeApplyPolicy(List<String> basePlan, String query, SearchPolicyDecision decision) {
        try {
            return searchPolicyEngine.apply(basePlan, query, decision);
        } catch (Exception e) {
            traceSearchPolicyFailure(query, "apply", e);
            return basePlan;
        }
    }

    private static void traceSearchPolicyFailure(String query, String stage, Throwable error) {
        TraceStore.put("web.analyze.searchPolicy.failed", true);
        TraceStore.put("web.analyze.searchPolicy.stage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("web.analyze.searchPolicy.failureReason", analyzeFailureReason(error));
        TraceStore.put("web.analyze.searchPolicy.errorType", analyzeErrorType(error));
        TraceStore.put("web.analyze.searchPolicy.queryHash12", hash12(query));
        TraceStore.put("web.analyze.searchPolicy.queryLength", query == null ? 0 : query.length());
    }

    private static void traceInterruptedPoll(String query, Throwable error) {
        TraceStore.put("web.await.analyze.interrupted", true);
        TraceStore.put("web.await.analyze.interrupted.reason", analyzeFailureReason(error));
        TraceStore.put("web.await.analyze.interrupted.errorType", analyzeErrorType(error));
        TraceStore.put("web.await.analyze.interrupted.queryHash12", hash12(query));
        TraceStore.put("web.await.analyze.interrupted.queryLength", query == null ? 0 : query.length());
    }

    private static void traceMetaIntParseFallback(String key, Throwable error) {
        TraceStore.put("web.analyze.metaInt.parseFallback", true);
        TraceStore.put("web.analyze.metaInt.parseFallback.key",
                SafeRedactor.traceLabelOrFallback(key, "unknown"));
        TraceStore.put("web.analyze.metaInt.parseFallback.errorType", "invalid_number");
    }

    private static void traceFallbackSearchFailure(String query, Throwable error) {
        TraceStore.put("web.analyze.fallbackSearch.failed", true);
        TraceStore.put("web.analyze.fallbackSearch.failureReason", analyzeFailureReason(error));
        TraceStore.put("web.analyze.fallbackSearch.errorType", analyzeErrorType(error));
        TraceStore.put("web.analyze.fallbackSearch.queryHash12", hash12(query));
        TraceStore.put("web.analyze.fallbackSearch.queryLength", query == null ? 0 : query.length());
    }

    private static void tracePlannedSearchFailure(String query, Throwable error) {
        TraceStore.put("web.analyze.plannedSearch.failed", true);
        TraceStore.put("web.analyze.plannedSearch.failureReason", analyzeFailureReason(error));
        TraceStore.put("web.analyze.plannedSearch.errorType", analyzeErrorType(error));
        TraceStore.put("web.analyze.plannedSearch.queryHash12", hash12(query));
        TraceStore.put("web.analyze.plannedSearch.queryLength", query == null ? 0 : query.length());
    }

    private static void tracePartialSearchFailure(String query, Throwable error) {
        TraceStore.put("web.analyze.partialSearch.failed", true);
        TraceStore.put("web.analyze.partialSearch.failureReason", analyzeFailureReason(error));
        TraceStore.put("web.analyze.partialSearch.errorType", analyzeErrorType(error));
        TraceStore.put("web.analyze.partialSearch.queryHash12", hash12(query));
        TraceStore.put("web.analyze.partialSearch.queryLength", query == null ? 0 : query.length());
    }

    private static void traceCancelFailure(String query, Throwable error) {
        TraceStore.put("web.await.analyze.cancelFailure", true);
        TraceStore.put("web.await.analyze.cancelFailure.reason", analyzeFailureReason(error));
        TraceStore.put("web.await.analyze.cancelFailure.errorType", analyzeErrorType(error));
        TraceStore.put("web.await.analyze.cancelFailure.queryHash12", hash12(query));
        TraceStore.put("web.await.analyze.cancelFailure.queryLength", query == null ? 0 : query.length());
    }

    private static String hash12(String value) {
        String hash = SafeRedactor.hash12(value);
        if (hash == null || hash.isBlank()) {
            return "";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private Optional<List<String>> tryFlattenBraveJson(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = stripCodeFence(raw);
        if (!looksLikeBraveJson(cleaned)) {
            return Optional.empty();
        }
        try {
            List<String> flattened = flattenBraveJson(cleaned, max);
            return (flattened == null || flattened.isEmpty()) ? Optional.empty() : Optional.of(flattened);
        } catch (Exception e) {
            // Only warn for JSON-looking payloads.
            log.warn("[AnalyzeWebSearchRetriever][nova] Failed to flatten Brave JSON snippet failureReason={} errorType={} snippetHash={} snippetLength={}",
                    analyzeFailureReason(e),
                    analyzeErrorType(e),
                    SafeRedactor.hashValue(cleaned),
                    cleaned.length());
            return Optional.empty();
        }
    }

    private boolean looksLikeBraveJson(String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        if (trimmed.length() < 100) {
            return false;
        }
        return trimmed.startsWith("{")
                && trimmed.contains("\"web\"")
                && trimmed.contains("\"results\"");
    }

    private List<String> flattenBraveJson(String json, int max) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode results = root.path("web").path("results");
        List<String> out = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode node : results) {
                String title = node.path("title").asText("");
                String desc = node.path("description").asText("");
                String url = node.path("url").asText("");

                StringBuilder sb = new StringBuilder();
                if (!title.isBlank()) sb.append(title.trim()).append("\n");
                if (!desc.isBlank()) sb.append(desc.trim()).append("\n");
                if (!url.isBlank()) sb.append("URL: ").append(url.trim());

                String snippet = sb.toString().trim();
                if (!snippet.isBlank()) {
                    out.add(snippet);
                }
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    private static String analyzeFailureReason(Throwable error) {
        Throwable root = rootCause(error);
        String type = root == null ? "" : root.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (type.contains("timeout")) {
            return "timeout";
        }
        if (type.contains("interrupted") || type.contains("cancellation")) {
            return "cancelled";
        }
        return "exception";
    }

    private static String analyzeErrorType(Throwable error) {
        Throwable root = rootCause(error);
        if (root instanceof InterruptedException || root instanceof java.util.concurrent.CancellationException) {
            return "cancelled";
        }
        String type = root == null ? "unknown" : root.getClass().getSimpleName();
        return SafeRedactor.traceLabelOrFallback(type, "unknown");
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cur = error;
        for (int i = 0; i < 8 && cur != null && cur.getCause() != null; i++) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static String stripCodeFence(String s) {
        String trimmed = (s == null) ? "" : s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }
        if (trimmed.endsWith("```")) {
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmed = trimmed.substring(0, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String extractUrl(String snippet) {
        if (snippet == null) {
            return null;
        }
        String s = snippet;

        int idx = s.indexOf("URL:");
        if (idx >= 0) {
            String rest = s.substring(idx + 4).trim();
            int newline = rest.indexOf('\n');
            if (newline >= 0) {
                rest = rest.substring(0, newline);
            }
            return cleanupUrl(rest);
        }

        Matcher m1 = HREF_DQ.matcher(s);
        if (m1.find()) {
            return cleanupUrl(m1.group(1));
        }
        Matcher m2 = HREF_SQ.matcher(s);
        if (m2.find()) {
            return cleanupUrl(m2.group(1));
        }

        Matcher m3 = BARE_URL.matcher(s);
        if (m3.find()) {
            return cleanupUrl(m3.group(1));
        }

        return null;
    }

    private static String cleanupUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        // strip trailing punctuation common in logs/markdown
        while (!u.isEmpty()) {
            char c = u.charAt(u.length() - 1);
            if (c == ')' || c == ']' || c == '}' || c == '>' || c == '.' || c == ',' || c == ';') {
                u = u.substring(0, u.length() - 1).trim();
                continue;
            }
            break;
        }
        if (u.length() < 8) return null;
        if (!u.toLowerCase(Locale.ROOT).startsWith("http")) return null;
        return u;
    }
}

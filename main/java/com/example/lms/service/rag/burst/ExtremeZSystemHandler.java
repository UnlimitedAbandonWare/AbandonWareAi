package com.example.lms.service.rag.burst;

import com.example.lms.nova.burst.QueryBurstExpander;
import com.example.lms.search.TraceStore;
import com.example.lms.service.TrainingService;
import com.example.lms.trace.SafeRedactor;
import ai.abandonware.nova.boot.exec.CancelShieldExecutorService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ExtremeZSystemHandler {
    private static final Logger log = LoggerFactory.getLogger(ExtremeZSystemHandler.class);
    private final com.example.lms.service.rag.burst.ExtremeZTrigger trigger;
    private final com.example.lms.service.rag.SelfAskPlanner selfAskPlanner;
    private final com.example.lms.service.rag.AnalyzeWebSearchRetriever webRetriever;
    private final com.example.lms.service.rag.LangChainRAGService ragService;
    private final com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser rrf;
    private final com.example.lms.service.rag.auth.AuthorityScorer authorityScorer;
    private final com.example.lms.service.rag.burst.ExtremeZProperties props;
    private final ExecutorService executor;
    private final TrainingService trainingService;
    private final QueryBurstExpander queryBurstExpander;
    private final boolean cancelShieldWrapped;
    private final String cancelShieldSkipReason;

    public ExtremeZSystemHandler(
        com.example.lms.service.rag.burst.ExtremeZTrigger trigger,
        com.example.lms.service.rag.SelfAskPlanner selfAskPlanner,
        com.example.lms.service.rag.AnalyzeWebSearchRetriever webRetriever,
        com.example.lms.service.rag.LangChainRAGService ragService,
        com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser rrf,
        com.example.lms.service.rag.auth.AuthorityScorer authorityScorer,
        com.example.lms.service.rag.burst.ExtremeZProperties props
    ){
        this(trigger, selfAskPlanner, webRetriever, ragService, rrf, authorityScorer, props, null, null);
    }

    public ExtremeZSystemHandler(
        com.example.lms.service.rag.burst.ExtremeZTrigger trigger,
        com.example.lms.service.rag.SelfAskPlanner selfAskPlanner,
        com.example.lms.service.rag.AnalyzeWebSearchRetriever webRetriever,
        com.example.lms.service.rag.LangChainRAGService ragService,
        com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser rrf,
        com.example.lms.service.rag.auth.AuthorityScorer authorityScorer,
        com.example.lms.service.rag.burst.ExtremeZProperties props,
        ExecutorService executor
    ){
        this(trigger, selfAskPlanner, webRetriever, ragService, rrf, authorityScorer, props, executor, null);
    }

    public ExtremeZSystemHandler(
        com.example.lms.service.rag.burst.ExtremeZTrigger trigger,
        com.example.lms.service.rag.SelfAskPlanner selfAskPlanner,
        com.example.lms.service.rag.AnalyzeWebSearchRetriever webRetriever,
        com.example.lms.service.rag.LangChainRAGService ragService,
        com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser rrf,
        com.example.lms.service.rag.auth.AuthorityScorer authorityScorer,
        com.example.lms.service.rag.burst.ExtremeZProperties props,
        ExecutorService executor,
        TrainingService trainingService
    ){
        this.trigger = trigger;
        this.selfAskPlanner = selfAskPlanner;
        this.webRetriever = webRetriever;
        this.ragService = ragService;
        this.rrf = rrf;
        this.authorityScorer = authorityScorer;
        this.props = props == null ? new ExtremeZProperties() : props;
        this.executor = shieldExecutor(executor);
        this.cancelShieldWrapped = this.executor instanceof CancelShieldExecutorService;
        this.cancelShieldSkipReason = executor == null ? "no_executor_sequential" : "";
        this.trainingService = trainingService;
        this.queryBurstExpander = new QueryBurstExpander(selfAskPlanner);
        TraceStore.put("extremez.systemHandler.wired", trigger != null);
        TraceStore.put("extremez.systemHandler.plannerWired", selfAskPlanner != null);
        TraceStore.put("extremez.systemHandler.webRetrieverWired", webRetriever != null);
        TraceStore.put("extremez.systemHandler.ragServiceWired", ragService != null);
        TraceStore.put("extremez.systemHandler.rrfWired", rrf != null);
        TraceStore.put("extremez.systemHandler.authorityScorerWired", authorityScorer != null);
        TraceStore.put("extremez.systemHandler.executorWired", executor != null);
        TraceStore.put("extremez.trainingRagProbe.wired", trainingService != null);
    }

    public void publishHarmonyTrace() {
        TraceStore.put("extremez.executor.cancelShieldWrapped", cancelShieldWrapped);
        TraceStore.put("extremeZ.cancelShieldWrapped", cancelShieldWrapped);
        TraceStore.put("extremeZ.cancelShield.skipReason", cancelShieldSkipReason);
        TraceStore.putIfAbsent("extremeZ.timeBudgetConsumedMs", 0L);
    }

    private static ExecutorService shieldExecutor(ExecutorService executor) {
        if (executor == null) {
            TraceStore.put("extremez.executor.cancelShieldWrapped", false);
            TraceStore.put("extremeZ.cancelShieldWrapped", false);
            TraceStore.put("extremeZ.cancelShield.skipReason", "no_executor_sequential");
            return null;
        }
        if (executor instanceof CancelShieldExecutorService) {
            TraceStore.put("extremez.executor.cancelShieldWrapped", true);
            TraceStore.put("extremeZ.cancelShieldWrapped", true);
            TraceStore.put("extremeZ.cancelShield.skipReason", "");
            return executor;
        }
        TraceStore.put("extremez.executor.cancelShieldWrapped", true);
        TraceStore.put("extremeZ.cancelShieldWrapped", true);
        TraceStore.put("extremeZ.cancelShield.skipReason", "");
        return new CancelShieldExecutorService(executor, "extremezSystemHandler");
    }

    public ExtremeZTrigger.Decision plan(String query,
                                         int baseDocCount,
                                         double authorityScore,
                                         double contradictionScore,
                                         boolean highRiskTail) {
        Object primaryMode = TraceStore.get("routing.executionPlan.primaryMode");
        String primary = primaryMode == null ? "" : String.valueOf(primaryMode).trim();
        if (!primary.isBlank() && !"EXTREMEZ".equalsIgnoreCase(primary)) {
            String safePrimary = SafeRedactor.traceLabelOrFallback(primary.toUpperCase(java.util.Locale.ROOT), "UNKNOWN");
            TraceStore.put("extremez.activated", false);
            TraceStore.put("extremeZ.activated", false);
            TraceStore.put("extremeZ.bypassReason", "suppressed_by_conflict_resolver");
            TraceStore.put("extremez.suppressedBy", "conflict_resolver_primary=" + safePrimary);
            return new ExtremeZTrigger.Decision(
                    false,
                    false,
                    false,
                    false,
                    false,
                    "suppressed_by_conflict_resolver",
                    com.example.lms.orchestration.ExecutionPlan.normal());
        }
        ExtremeZTrigger.Decision decision = (trigger == null)
                ? new ExtremeZTrigger.Decision(
                        false,
                        false,
                        false,
                        false,
                        false,
                        "trigger_missing",
                        com.example.lms.orchestration.ExecutionPlan.normal())
                : trigger.evaluate(query, baseDocCount, authorityScore, contradictionScore, highRiskTail);
        try {
            TraceStore.put("extremez.systemHandler.plan.primaryMode", decision.plan().primaryMode().name());
            TraceStore.put("extremez.systemHandler.plan.reason",
                    SafeRedactor.traceLabelOrFallback(decision.reason(), "unknown"));
            TraceStore.put("extremez.activated", decision.activate());
            boolean extremeZActive = decision.plan().extremeZEnabled();
            String safeReason = SafeRedactor.traceLabelOrFallback(decision.reason(), "unknown");
            TraceStore.put("extremeZ.activated", extremeZActive);
            TraceStore.put("extremeZ.bypassReason", extremeZActive ? "" : safeReason);
            if (decision.activate()) {
                TraceStore.put("extremez.triggerReasons",
                        safeReason);
                TraceStore.put("extremeZ.triggerReasons", safeReason);
            }
        } catch (Throwable e) {
            traceError("extremez.systemHandler.planTraceError", e);
            log.debug("[ExtremeZSystemHandler] fail-soft stage={}", "plan.trace");
        }
        return decision;
    }

    public List<Content> execute(String query, ExtremeZTrigger.Decision decision) {
        if (decision == null || !decision.activate()) {
            traceExecuteSkipped(decision == null ? "missing_decision" : decision.reason());
            return List.of();
        }
        if (!allowsExtremeZ(decision)) {
            traceExecuteSkipped("plan_" + decision.plan().primaryMode().name());
            TraceStore.put("extremez.suppressedBy", decision.plan().primaryMode().name());
            TraceStore.put("extremez.activated", false);
            TraceStore.put("extremeZ.activated", false);
            return List.of();
        }

        List<String> subQueries;
        try {
            subQueries = queryBurstExpander.expand(query, 1, props.getMaxSubQueries());
        } catch (RuntimeException e) {
            log.debug("[ExtremeZSystemHandler] fail-soft stage={}", "planner");
            traceError("extremez.execute.plannerError", e);
            traceExecuteSkipped("planner_error");
            return List.of();
        }
        if (subQueries.isEmpty()) {
            traceExecuteSkipped("blank_query");
            return List.of();
        }
        TraceStore.put("extremez.execute.subQueryCount", subQueries.size());
        TraceStore.put("extremeZ.subQueryCount", subQueries.size());
        TraceStore.put("extremeZ.parallelFanout.queryCount", subQueries.size());
        TraceStore.put("extremeZ.cancelShield.interruptsSuppressed", 0);
        TraceStore.put("extremeZ.cancel.interruptSuppressed", false);

        List<List<Content>> buckets = new ArrayList<>();
        List<Content> merged = new ArrayList<>();
        ContentRetriever vectorRetriever = null;
        if (ragService != null) {
            try {
                vectorRetriever = ragService.asContentRetriever("extremez");
            } catch (RuntimeException e) {
                log.debug("[ExtremeZSystemHandler] fail-soft stage={}", "vectorRetriever");
                traceError("extremez.execute.vectorRetrieverError", e);
            }
        }

        retrieveFanout(subQueries, vectorRetriever, buckets, merged);

        TraceStore.put("extremeZ.parallelFanout.mergedCount", merged.size());
        List<Content> refined = refine(buckets, merged);
        TraceStore.put("extremez.execute.rawCount", merged.size());
        TraceStore.put("extremez.execute.refinedCount", refined.size());
        TraceStore.put("extremeZ.outCount", refined.size());
        TraceStore.put("extremez.execute.activated", true);
        TraceStore.put("extremez.activated", true);
        TraceStore.put("extremeZ.activated", true);
        TraceStore.put("extremez.mergedDocCount", merged.size());
        TraceStore.put("extremeZ.mergedDocCount", merged.size());
        boolean rrfApplied = rrf != null && buckets.size() >= 2 && !merged.isEmpty();
        TraceStore.put("extremez.rrfApplied", rrfApplied);
        TraceStore.put("extremeZ.rrfApplied", rrfApplied);
        TraceStore.put("extremeZ.bypassReason", "");
        return refined;
    }

    private static boolean allowsExtremeZ(ExtremeZTrigger.Decision decision) {
        return decision != null
                && decision.plan() != null
                && decision.plan().extremeZEnabled();
    }

    private void retrieveFanout(
            List<String> subQueries,
            ContentRetriever vectorRetriever,
            List<List<Content>> buckets,
            List<Content> merged) {
        if (executor == null || subQueries == null || subQueries.size() <= 1) {
            retrieveSequential(subQueries, vectorRetriever, buckets, merged);
            return;
        }

        List<Callable<List<Content>>> tasks = new ArrayList<>();
        AtomicInteger trainingRagReturned = new AtomicInteger();
        for (String subQuery : subQueries) {
            String planned = subQuery;
            tasks.add(() -> retrieveWeb(new Query(planned)));
            tasks.add(() -> retrieveVector(vectorRetriever, new Query(planned)));
            if (trainingService != null) {
                tasks.add(() -> {
                    List<Content> out = retrieveTrainingRag(planned);
                    trainingRagReturned.addAndGet(out.size());
                    return out;
                });
            }
        }

        long timeoutMs = props.getParallelTimeoutMs();
        TraceStore.put("extremez.execute.parallel.used", true);
        TraceStore.put("extremez.execute.parallel.taskCount", tasks.size());
        TraceStore.put("extremez.parallelBranchCount", tasks.size());
        TraceStore.put("extremeZ.parallelBranchCount", tasks.size());
        TraceStore.put("extremez.execute.parallel.timeoutMs", timeoutMs);
        TraceStore.put("extremez.timeoutMs", timeoutMs);
        TraceStore.put("extremeZ.timeoutMs", timeoutMs);
        TraceStore.put("extremeZ.interrupted", false);
        TraceStore.put("extremeZ.interruptPropagated", false);
        long startedNanos = System.nanoTime();
        try {
            List<Future<List<Content>>> futures = executor.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS);
            int cancelled = 0;
            for (Future<List<Content>> future : futures) {
                if (future == null) {
                    continue;
                }
                if (future.isCancelled()) {
                    cancelled++;
                    continue;
                }
                try {
                    addBucket(buckets, merged, future.get());
                } catch (ExecutionException e) {
                    log.debug("[ExtremeZSystemHandler] fail-soft stage={}", "parallel.task");
                    traceError("extremez.execute.parallel.taskError", e.getCause() == null ? e : e.getCause());
                }
            }
            if (cancelled > 0) {
                TraceStore.put("extremez.execute.parallel.cancelled", cancelled);
                TraceStore.put("extremeZ.cancelSuppressed", true);
                TraceStore.put("extremeZ.cancelSuppressed.count", cancelled);
                TraceStore.put("extremeZ.cancelSuppressed.errorType", "cancel_shield_boundary");
                TraceStore.put("extremeZ.cancelShield.interruptsSuppressed", cancelled);
                TraceStore.put("extremeZ.cancel.interruptSuppressed", true);
            } else {
                TraceStore.put("extremeZ.cancelSuppressed", false);
                TraceStore.put("extremeZ.cancelSuppressed.count", 0);
                TraceStore.put("extremeZ.cancelShield.interruptsSuppressed", 0);
                TraceStore.put("extremeZ.cancel.interruptSuppressed", false);
            }
        } catch (InterruptedException e) {
            log.debug("[ExtremeZSystemHandler] fail-soft stage={}", "parallel.interrupted");
            TraceStore.put("extremez.execute.parallel.interrupted", true);
            TraceStore.put("extremeZ.interrupted", true);
            TraceStore.put("extremeZ.interruptPropagated", false);
            TraceStore.put("extremeZ.interrupt.suppressed.reason", "cancel_shield_boundary");
            TraceStore.put("extremeZ.interrupt.suppressed.errorType", "cancel_shield_boundary");
            TraceStore.put("extremeZ.cancelSuppressed", true);
            TraceStore.put("extremeZ.cancelSuppressed.count", 1);
            TraceStore.put("extremeZ.cancelSuppressed.errorType", "cancel_shield_boundary");
            TraceStore.put("extremeZ.cancelShield.interruptsSuppressed", 1);
            TraceStore.put("extremeZ.cancel.interruptSuppressed", true);
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
            TraceStore.put("extremeZ.timeBudgetConsumedMs", elapsedMs);
            if (trainingService != null) {
                TraceStore.put("extremez.trainingRagProbe.enabled", true);
                TraceStore.put("extremez.trainingRagProbe.returnedCount", trainingRagReturned.get());
            }
        }
    }

    private void retrieveSequential(
            List<String> subQueries,
            ContentRetriever vectorRetriever,
            List<List<Content>> buckets,
            List<Content> merged) {
        if (subQueries == null) {
            return;
        }
        TraceStore.put("extremez.execute.parallel.used", false);
        TraceStore.put("extremez.parallelBranchCount", 0);
        TraceStore.put("extremeZ.parallelBranchCount", 0);
        int trainingRagReturned = 0;
        for (String subQuery : subQueries) {
            Query q = new Query(subQuery);
            addBucket(buckets, merged, retrieveWeb(q));
            addBucket(buckets, merged, retrieveVector(vectorRetriever, q));
            List<Content> trainingDocs = retrieveTrainingRag(subQuery);
            trainingRagReturned += trainingDocs.size();
            addBucket(buckets, merged, trainingDocs);
        }
        if (trainingService != null) {
            TraceStore.put("extremez.trainingRagProbe.enabled", true);
            TraceStore.put("extremez.trainingRagProbe.returnedCount", trainingRagReturned);
            TraceStore.put("extremez.sequential.trainingRagCount", trainingRagReturned);
            TraceStore.put("extremeZ.sequential.trainingRagCount", trainingRagReturned);
        }
    }

    private List<Content> retrieveWeb(Query query) {
        if (webRetriever == null) {
            return List.of();
        }
        try {
            List<Content> out = webRetriever.retrieve(query);
            return out == null ? List.of() : out;
        } catch (RuntimeException e) {
            log.debug("[ExtremeZSystemHandler] fail-soft stage={}", "web");
            traceError("extremez.execute.webError", e);
            return List.of();
        }
    }

    private List<Content> retrieveVector(ContentRetriever vectorRetriever, Query query) {
        if (vectorRetriever == null) {
            return List.of();
        }
        try {
            List<Content> out = vectorRetriever.retrieve(query);
            return out == null ? List.of() : out;
        } catch (RuntimeException e) {
            log.debug("[ExtremeZSystemHandler] fail-soft stage={}", "vector");
            traceError("extremez.execute.vectorError", e);
            return List.of();
        }
    }

    private List<Content> retrieveTrainingRag(String query) {
        if (trainingService == null) {
            TraceStore.put("extremez.trainingRagProbe.enabled", false);
            return List.of();
        }
        try {
            List<String> samples = trainingService.findTrainingRagSamples(query, trainingRagProbeLimit());
            return samples == null ? List.of() : samples.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(Content::from)
                    .toList();
        } catch (RuntimeException e) {
            log.debug("[ExtremeZSystemHandler] fail-soft stage={}", "trainingRag");
            traceError("extremez.execute.trainingRagError", e);
            return List.of();
        }
    }

    private int trainingRagProbeLimit() {
        return Math.max(1, Math.min(3, props.getMaxMergedDocs()));
    }

    private List<Content> refine(List<List<Content>> buckets, List<Content> merged) {
        if (rrf == null || buckets == null || buckets.size() < 2 || merged == null || merged.isEmpty()) {
            return merged == null ? List.of() : List.copyOf(merged);
        }
        try {
            List<Content> fused = rrf.fuse(buckets, props.getMaxMergedDocs());
            return fused == null ? List.of() : fused;
        } catch (RuntimeException e) {
            log.debug("[ExtremeZSystemHandler] fail-soft stage={}", "rrf");
            traceError("extremez.execute.rrfError", e);
            return List.copyOf(merged);
        }
    }

    private static void addBucket(List<List<Content>> buckets, List<Content> merged, List<Content> bucket) {
        if (bucket == null || bucket.isEmpty()) {
            return;
        }
        List<Content> copy = new ArrayList<>(bucket);
        buckets.add(copy);
        merged.addAll(copy);
    }

    private static void traceExecuteSkipped(String reason) {
        TraceStore.put("extremez.execute.skipped", true);
        TraceStore.put("extremeZ.outCount", 0);
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        TraceStore.put("extremez.execute.reason", safeReason);
        TraceStore.put("extremeZ.activated", false);
        TraceStore.put("extremeZ.bypassReason", safeReason);
    }

    private static void traceError(String key, Throwable error) {
        TraceStore.put(key, SafeRedactor.traceLabelOrFallback(error == null ? null : error.getClass().getSimpleName(), "unknown"));
        TraceStore.put(key + "Hash", SafeRedactor.hashValue(messageOf(error)));
        TraceStore.put(key + "Length", messageLength(error));
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
}

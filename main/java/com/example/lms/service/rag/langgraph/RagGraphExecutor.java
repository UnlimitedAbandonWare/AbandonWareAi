package com.example.lms.service.rag.langgraph;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.auth.DomainWhitelist;
import com.example.lms.service.rag.handler.EvidenceRepairHandler;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryTrace;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.apache.commons.codec.digest.DigestUtils;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Component
public class RagGraphExecutor {

    private static final Logger log = LoggerFactory.getLogger(RagGraphExecutor.class);
    private static final String GRAPH_ID = "rag-langgraph";
    private static final List<String> QUALITY_REPAIR_LABELS = List.of(
            "empty_results",
            "kg_neo4j_degraded",
            "kg_starvation",
            "kg_final_drop",
            "anchor_drift_high",
            "source_collapse",
            "stage_drop_high",
            "offline_texture_stale");
    private static final List<String> KG_AXIS_TRACE_KEYS = List.of(
            "schemaVersion",
            "status",
            "retrievedCount",
            "finalCount",
            "finalRetention",
            "score",
            "kgPoolCount",
            "kgFinalCount",
            "kgScoreMean",
            "kgScoreP95",
            "kgFinalRetention",
            "graphScore",
            "graphOpportunity",
            "neo4jStatus",
            "neo4jDisabledReason",
            "neo4jFailureClass",
            "signals");
    private static final List<String> SCORECARD_TRACE_KEYS = List.of(
            "schemaVersion",
            "status",
            "reasonCode",
            "thresholdLabels");

    private final UnifiedRagOrchestrator orchestrator;
    private final ObjectProvider<EvidenceRepairHandler> repairHandlerProvider;
    private final RagGraphProperties properties;
    private final ObjectProvider<LangGraphNodeSnapshotRecorder> snapshotRecorderProvider;
    private final ExecutorService graphExecutor;
    @Autowired(required = false)
    private DomainWhitelist domainWhitelist;
    private volatile CompiledGraph<RagGraphState> compiledGraph;
    private volatile CheckpointStatus checkpointStatus = CheckpointStatus.memory();

    @Autowired
    public RagGraphExecutor(UnifiedRagOrchestrator orchestrator,
                            ObjectProvider<EvidenceRepairHandler> repairHandlerProvider,
                            RagGraphProperties properties,
                            ObjectProvider<LangGraphNodeSnapshotRecorder> snapshotRecorderProvider,
                            @Qualifier("ragGraphWorkerExecutor") ExecutorService graphExecutor) {
        this.orchestrator = orchestrator;
        this.repairHandlerProvider = repairHandlerProvider;
        this.properties = properties;
        this.snapshotRecorderProvider = snapshotRecorderProvider;
        this.graphExecutor = graphExecutor;
    }

    public RagGraphExecutor(UnifiedRagOrchestrator orchestrator,
                            ObjectProvider<EvidenceRepairHandler> repairHandlerProvider,
                            RagGraphProperties properties,
                            ObjectProvider<LangGraphNodeSnapshotRecorder> snapshotRecorderProvider) {
        this(orchestrator, repairHandlerProvider, properties, snapshotRecorderProvider, null);
    }

    public RagGraphExecutor(UnifiedRagOrchestrator orchestrator,
                            ObjectProvider<EvidenceRepairHandler> repairHandlerProvider,
                            RagGraphProperties properties) {
        this(orchestrator, repairHandlerProvider, properties, null, null);
    }

    public QueryResponse execute(QueryRequest request) {
        long configuredTimeoutMs = Math.max(0L, properties.getTimeoutMs());
        TimeBudget requestBudget = TimeBudgetContext.get();
        if (configuredTimeoutMs <= 0L && (requestBudget == null || graphExecutor == null)) {
            return executeInternal(request);
        }

        long timeoutMs = effectiveTimeoutMs(configuredTimeoutMs, requestBudget);
        if (timeoutMs <= 0L) {
            recordTimeoutCancellation(0L, false, false, false, false);
            throw new IllegalStateException("LangGraph RAG execution timed out after 0ms");
        }
        if (graphExecutor == null) {
            return executorFailSoft(request, "graph_executor_unavailable");
        }

        AtomicBoolean workerStarted = new AtomicBoolean(false);
        AtomicBoolean workerFinished = new AtomicBoolean(false);
        CompletableFuture<QueryResponse> future;
        try {
            future = CompletableFuture.supplyAsync(
                    ContextPropagation.wrapSupplier(() -> {
                        workerStarted.set(true);
                        try {
                            return executeInternal(request);
                        } finally {
                            workerFinished.set(true);
                        }
                    }),
                    graphExecutor);
        } catch (RejectedExecutionException e) {
            return executorFailSoft(request, "graph_executor_saturated");
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            boolean cancelAccepted = future.cancel(false);
            WorkerObservation observation = snapshotWorkerObservation(workerStarted, workerFinished);
            recordTimeoutCancellation(timeoutMs, true, cancelAccepted,
                    observation.started(), observation.finished());
            throw new IllegalStateException("LangGraph RAG execution timed out after " + timeoutMs + "ms", e);
        } catch (InterruptedException e) {
            boolean cancelAccepted = future.cancel(false);
            WorkerObservation observation = snapshotWorkerObservation(workerStarted, workerFinished);
            recordWorkerCancellation(true, cancelAccepted, observation.started(), observation.finished());
            throw terminalInterruption(e);
        } catch (Exception e) {
            propagateTerminalCancellation(e);
            throw new IllegalStateException("LangGraph RAG execution failed", e);
        }
    }

    private static long effectiveTimeoutMs(long configuredTimeoutMs, TimeBudget requestBudget) {
        if (requestBudget == null) {
            return Math.max(0L, configuredTimeoutMs);
        }
        long remainingMs = Math.max(0L, requestBudget.remainingMillis());
        if (configuredTimeoutMs <= 0L) {
            return remainingMs;
        }
        return Math.min(configuredTimeoutMs, remainingMs);
    }

    private static WorkerObservation snapshotWorkerObservation(AtomicBoolean workerStarted,
                                                               AtomicBoolean workerFinished) {
        boolean finished = workerFinished.get();
        boolean started = finished || workerStarted.get();
        return new WorkerObservation(started, finished);
    }

    private static QueryResponse executorFailSoft(QueryRequest request, String reason) {
        String safeReason = safeFailureReason(reason);
        QueryResponse response = emptyResponse(request, safeReason);
        ensureDebug(response).put("langgraph.failureClass", safeReason);
        try {
            TraceStore.put("langgraph.executor.reason", safeReason);
            TraceStore.inc("langgraph.executor.rejected.count");
        } catch (Throwable ignore) {
            log.debug("[LangGraph] fail-soft stage=executor.trace err=trace-failure");
        }
        return response;
    }

    private static void recordTimeoutCancellation(long timeoutMs,
                                                  boolean cancelRequested,
                                                  boolean cancelAccepted,
                                                  boolean workerStarted,
                                                  boolean workerFinished) {
        try {
            long safeTimeoutMs = Math.max(0L, timeoutMs);
            TraceStore.put("langgraph.timeout", true);
            TraceStore.put("langgraph.failureClass", "timeout");
            TraceStore.put("langgraph.timeoutMs", safeTimeoutMs);
            TraceStore.put("langgraph.cancelMode", "no_interrupt");
            recordWorkerCancellation(cancelRequested, cancelAccepted, workerStarted, workerFinished);
            TraceStore.inc("langgraph.timeout.count");
            TraceStore.append("langgraph.timeout.events", Map.of(
                    "failureClass", "timeout",
                    "timeoutMs", safeTimeoutMs,
                    "cancelMode", "no_interrupt",
                    "cancelRequested", cancelRequested,
                    "cancelAccepted", cancelAccepted,
                    "workerStarted", workerStarted,
                    "workerFinished", workerFinished,
                    "workerTermination", workerTermination(workerStarted, workerFinished)));
        } catch (Throwable ignore) {
            log.debug("[LangGraph] fail-soft stage=timeout.trace err=trace-failure");
        }
    }

    private static void recordWorkerCancellation(boolean cancelRequested,
                                                 boolean cancelAccepted,
                                                 boolean workerStarted,
                                                 boolean workerFinished) {
        TraceStore.put("langgraph.cancelRequested", cancelRequested);
        TraceStore.put("langgraph.cancelAccepted", cancelAccepted);
        TraceStore.put("langgraph.workerStarted", workerStarted);
        TraceStore.put("langgraph.workerFinished", workerFinished);
        TraceStore.put("langgraph.workerTermination", workerTermination(workerStarted, workerFinished));
    }

    private static String workerTermination(boolean workerStarted, boolean workerFinished) {
        if (workerFinished) {
            return "terminated";
        }
        return workerStarted ? "unfinished" : "not_started";
    }

    private record WorkerObservation(boolean started, boolean finished) {
    }

    private static void recordCompiledInvokeSuccess(Map<String, Object> debug,
                                                    QueryRequest request,
                                                    String threadId,
                                                    RagGraphControlPolicy.Decision decision) {
        if (debug == null) {
            return;
        }
        String threadIdHash = hashThreadId(threadId);
        String queryHash = SafeRedactor.hash12(request == null ? null : request.query);
        debug.put("langgraph.invoke.source", "compiled_graph_invoke");
        debug.put("langgraph.invoke.fallbackTriggered", false);
        debug.put("langgraph.invoke.trigger", "none");
        debug.put("langgraph.invoke.failureClass", "none");
        debug.put("langgraph.invoke.threadIdHash", threadIdHash);
        debug.put("langgraph.invoke.queryHash", queryHash);
        debug.put("langgraph.invoke.primaryCandidate", decision != null && decision.promotionEligible());
    }

    private static void recordInvokeFallback(Map<String, Object> debug,
                                             QueryRequest request,
                                             String threadId,
                                             InvokeFallbackTrigger trigger) {
        InvokeFallbackTrigger safeTrigger = trigger == null
                ? InvokeFallbackTrigger.of("graph_invoke_unknown", "langgraph-invoke-error", null)
                : trigger;
        String threadIdHash = hashThreadId(threadId);
        String queryHash = SafeRedactor.hash12(request == null ? null : request.query);
        debug.put("langgraph.invoke.source", safeTrigger.source());
        debug.put("langgraph.invoke.fallbackTriggered", true);
        debug.put("langgraph.invoke.trigger", safeTrigger.trigger());
        debug.put("langgraph.invoke.failureClass", safeTrigger.failureClass());
        debug.put("langgraph.invoke.threadIdHash", threadIdHash);
        debug.put("langgraph.invoke.queryHash", queryHash);
        debug.put("langgraph.invoke.primaryCandidate", false);
        debug.put("langgraph.invoke.promotionBlocker", "invoke_fallback");
        if (!safeTrigger.exception().isBlank()) {
            debug.put("langgraph.invoke.exception", safeTrigger.exception());
        }
        try {
            TraceStore.put("langgraph.invoke.source", safeTrigger.source());
            TraceStore.put("langgraph.invoke.fallbackTriggered", true);
            TraceStore.put("langgraph.invoke.trigger", safeTrigger.trigger());
            TraceStore.put("langgraph.invoke.failureClass", safeTrigger.failureClass());
            TraceStore.put("langgraph.invoke.threadIdHash", threadIdHash);
            TraceStore.put("langgraph.invoke.queryHash", queryHash);
            TraceStore.put("langgraph.invoke.primaryCandidate", false);
            TraceStore.inc("langgraph.invoke.fallback.count");
            TraceStore.append("langgraph.invoke.fallback.events", safeTrigger.toTraceEvent(threadIdHash, queryHash));
        } catch (Throwable ignore) {
            log.debug("[LangGraph] fail-soft stage=invokeFallback.trace err=trace-failure");
        }
    }

    public QueryResponse executeOfflineReplay(QueryRequest request) {
        if (request == null || !request.seedOnly) {
            throw new IllegalArgumentException("Offline LangGraph replay requires seedOnly=true");
        }
        boolean hasSeedCandidates = request.seedCandidates != null && !request.seedCandidates.isEmpty();
        boolean hasSeedWeb = request.seedWeb != null && !request.seedWeb.isEmpty();
        boolean hasSeedVector = request.seedVector != null && !request.seedVector.isEmpty();
        if (!hasSeedCandidates && !hasSeedWeb && !hasSeedVector) {
            throw new IllegalArgumentException("Offline LangGraph replay requires at least one seed fixture");
        }
        return executeInternal(request);
    }

    private QueryResponse executeInternal(QueryRequest request) {
        QueryRequest safeRequest = request == null ? new QueryRequest() : request;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(RagGraphState.QUERY, safeRequest.query);
        input.put(RagGraphState.PLAN_ID, safeRequest.planId);
        input.put(RagGraphState.REQUEST, safeRequest);
        input.put(RagGraphState.DEBUG, new LinkedHashMap<String, Object>());

        String threadId = resolveThreadId(safeRequest);
        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId(threadId)
                .graphId(GRAPH_ID)
                .build();
        Optional<RagGraphState> result;
        try {
            result = graph().invoke(input, runnableConfig);
        } catch (NullPointerException e) {
            TraceStore.put("langgraph.invoke.suppressed.npe", true);
            TraceStore.put("langgraph.invoke.suppressed.npe.errorType", "NullPointerException");
            return executeSequentialFallback(safeRequest, threadId,
                    InvokeFallbackTrigger.of("graph_invoke_npe", "langgraph-invoke-null", e), e);
        } catch (Exception e) {
            propagateTerminalCancellation(e);
            TraceStore.put("langgraph.invoke.suppressed.error", true);
            TraceStore.put("langgraph.invoke.suppressed.error.errorType",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"));
            return executeSequentialFallback(safeRequest, threadId,
                    InvokeFallbackTrigger.of("graph_invoke_error", "langgraph-invoke-error", e), e);
        }
        if (result == null || result.isEmpty()) {
            return executeSequentialFallback(safeRequest, threadId,
                    InvokeFallbackTrigger.of(result == null ? "graph_invoke_null_result" : "graph_invoke_empty",
                            "langgraph-invoke-empty", null), null);
        }
        RagGraphState state = result.get();
        QueryResponse response = state.response();
        if (response == null) {
            return executeSequentialFallback(safeRequest, threadId,
                    InvokeFallbackTrigger.of("graph_no_response", "langgraph-no-response", null), null);
        }
        Map<String, Object> debug = ensureDebug(response);
        debug.put("langgraph.enabled", true);
        recordCompiledInvokeSuccess(debug, safeRequest, threadId, state.controlDecision());
        addCheckpointDebug(debug, threadId);
        if (!state.failureReason().isBlank()) {
            debug.put("langgraph.failureReason", safeFailureReason(state.failureReason()));
        }
        RagGraphControlPolicy.writeDebug(debug, state.controlDecision());
        return response;
    }

    private QueryResponse executeSequentialFallback(QueryRequest request,
                                                    String threadId,
                                                    InvokeFallbackTrigger trigger,
                                                    Exception cause) {
        String invokeFailureReason = trigger == null ? "graph_invoke_unknown" : trigger.trigger();
        String safeInvokeFailureReason = safeFailureReason(invokeFailureReason);
        if (cause == null) {
            log.debug("[AWX2AF2][langgraph] invoke fail-soft fallback reason={}", safeInvokeFailureReason);
        } else {
            log.debug("[AWX2AF2][langgraph] invoke fail-soft fallback reason={} errorType={} errorHash={} errorLength={}",
                    safeInvokeFailureReason,
                    cause.getClass().getSimpleName(),
                    SafeRedactor.hashValue(messageOf(cause)),
                    messageLength(cause));
        }

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("langgraph.enabled", true);
        debug.put("langgraph.fallback", "sequential");
        recordInvokeFallback(debug, request, threadId, trigger);
        debug.put("langgraph.invokeFailureReason", safeFailureReason(invokeFailureReason));
        debug.put("langgraph.node.prepare", "ok");
        Map<String, Object> prepareInput = new LinkedHashMap<>();
        putQuerySummary(prepareInput, request == null ? null : request.query);
        prepareInput.put("request", requestSummary(request));
        recordNodeSnapshot("prepare", prepareInput, Map.of(RagGraphState.DEBUG, debug));

        QueryRequest effectiveRequest = request == null ? new QueryRequest() : request;
        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.decide(effectiveRequest, debug);
        decision = transition(decision, decision.failureAction(), invokeFailureReason, "invoke_fallback");
        debug.put("langgraph.node.decide_control", "ok");
        RagGraphControlPolicy.writeDebug(debug, decision);
        recordNodeSnapshot("decide_control", prepareInput, controlUpdates(decision, debug));

        effectiveRequest = RagGraphControlPolicy.applyPolicy(decision, effectiveRequest);
        debug.put("langgraph.node.apply_policy", "ok");
        RagGraphControlPolicy.writeDebug(debug, decision);
        recordNodeSnapshot("apply_policy", prepareInput, updates(
                RagGraphState.REQUEST, effectiveRequest,
                RagGraphState.DEBUG, debug,
                RagGraphState.CONTROL_DECISION, decision,
                RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                RagGraphState.TRANSITION_TRACE, decision.transitionTrace()));

        QueryResponse response;
        String failureReason = "";
        try {
            Map<String, Object> retrieveInput = new LinkedHashMap<>(prepareInput);
            retrieveInput.put("debug", safeDebug(debug));
            QueryTrace trace = orchestrator.queryWithTrace(effectiveRequest);
            response = trace != null ? trace.response : null;
            if (response == null) {
                response = emptyResponse(effectiveRequest, invokeFailureReason);
                failureReason = invokeFailureReason;
            }
            ensureDebug(response).putAll(debug);
            ensureDebug(response).put("langgraph.node.retrieve", "ok");
            recordNodeSnapshot("retrieve", retrieveInput, updates(
                    RagGraphState.TRACE, trace,
                    RagGraphState.RESPONSE, response,
                    RagGraphState.DEBUG, ensureDebug(response)));
        } catch (Exception e) {
            propagateTerminalCancellation(e);
            failureReason = "retrieve_error:" + ((e instanceof CancellationException || e instanceof InterruptedException)
                    ? "cancelled"
                    : e.getClass().getSimpleName());
            response = emptyResponse(effectiveRequest, invokeFailureReason);
            ensureDebug(response).putAll(debug);
            ensureDebug(response).put("langgraph.node.retrieve", failureReason);
            recordNodeSnapshot("retrieve", prepareInput, Map.of(
                    RagGraphState.FAILURE_REASON, failureReason,
                    RagGraphState.RESPONSE, response,
                    RagGraphState.DEBUG, ensureDebug(response)));
            log.debug("[AWX2AF2][langgraph] sequential fallback retrieve failed reason={}", failureReason);
        }

        Map<String, Object> responseDebug = ensureDebug(response);
        int resultCount = response.results == null ? 0 : response.results.size();
        String qualityReason = qualityGateReason(response, resultCount,
                effectiveRequest == null ? "" : effectiveRequest.query);
        responseDebug.put("langgraph.node.quality_gate.resultCount", resultCount);
        responseDebug.put("langgraph.node.quality_gate.reason", qualityReason);
        responseDebug.put("langgraph.node.quality_gate.degraded", !qualityReason.isBlank());
        Map<String, Object> qualityInput = new LinkedHashMap<>(prepareInput);
        qualityInput.put("response", responseSummary(response));
        qualityInput.put("docs", docsFromResponse(response));
        if (resultCount == 0) {
            responseDebug.putIfAbsent("langgraph.emptyReason", safeFailureReason(invokeFailureReason));
        }
        decision = RagGraphControlPolicy.afterQuality(
                decision,
                effectiveRequest,
                response,
                failureReason,
                qualityReason,
                resultCount);
        RagGraphControlPolicy.writeDebug(responseDebug, decision);
        failureReason = decision.failureAction() == RagGraphControlPolicy.FailureAction.FINALIZE
                ? ""
                : decision.reasonCode();
        recordNodeSnapshot("quality_gate", qualityInput, Map.of(
                RagGraphState.FAILURE_REASON, failureReason,
                RagGraphState.DEBUG, responseDebug,
                RagGraphState.CONTROL_DECISION, decision,
                RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                RagGraphState.TRANSITION_TRACE, decision.transitionTrace()));

        String route = RagGraphControlPolicy.route(decision);
        if ("strict_verify".equals(route)) {
            decision = RagGraphControlPolicy.afterStrictVerify(decision, response);
            boolean passed = decision.failureAction() == RagGraphControlPolicy.FailureAction.FINALIZE;
            responseDebug.put("langgraph.node.strict_verify", "ok");
            responseDebug.put("langgraph.strictVerify.passed", passed);
            responseDebug.put("langgraph.strictVerify.reasonCode", decision.reasonCode());
            responseDebug.put("langgraph.strictVerify.resultCount", resultCount);
            RagGraphControlPolicy.writeDebug(responseDebug, decision);
            failureReason = passed ? "" : decision.reasonCode();
            recordNodeSnapshot("strict_verify", qualityInput, Map.of(
                    RagGraphState.FAILURE_REASON, failureReason,
                    RagGraphState.DEBUG, responseDebug,
                    RagGraphState.CONTROL_DECISION, decision,
                    RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                    RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                    RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                    RagGraphState.TRANSITION_TRACE, decision.transitionTrace()));
            route = RagGraphControlPolicy.route(decision);
        }

        if ("repair".equals(route)) {
            decision = runSequentialRepair(effectiveRequest, response, responseDebug, decision);
            RagGraphControlPolicy.writeDebug(responseDebug, decision);
            failureReason = decision.failureAction() == RagGraphControlPolicy.FailureAction.FINALIZE
                    ? ""
                    : decision.reasonCode();
            recordNodeSnapshot("repair", qualityInput, Map.of(
                    RagGraphState.RESPONSE, response,
                    RagGraphState.FAILURE_REASON, failureReason,
                    RagGraphState.DEBUG, responseDebug,
                    RagGraphState.CONTROL_DECISION, decision,
                    RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                    RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                    RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                    RagGraphState.TRANSITION_TRACE, decision.transitionTrace()));
            route = RagGraphControlPolicy.route(decision);
        }

        if ("fail_soft_finalize".equals(route)) {
            String reason = failureReason.isBlank() ? decision.reasonCode() : failureReason;
            responseDebug.put("langgraph.node.fail_soft_finalize", "ok");
            responseDebug.put("langgraph.failSoft.reasonCode", safeFailureReason(reason));
            decision = transition(decision,
                    RagGraphControlPolicy.FailureAction.FAIL_SOFT_FINALIZE,
                    reason,
                    "fail_soft_finalize");
            RagGraphControlPolicy.writeDebug(responseDebug, decision);
            failureReason = reason;
            recordNodeSnapshot("fail_soft_finalize", qualityInput, Map.of(
                    RagGraphState.RESPONSE, response,
                    RagGraphState.FAILURE_REASON, failureReason,
                    RagGraphState.DEBUG, responseDebug,
                    RagGraphState.CONTROL_DECISION, decision,
                    RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                    RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                    RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                    RagGraphState.TRANSITION_TRACE, decision.transitionTrace()));
        }
        if (!failureReason.isBlank()) {
            responseDebug.put("langgraph.failureReason", safeFailureReason(failureReason));
        }
        sanitizePublicEvalDebug(responseDebug);
        responseDebug.put("langgraph.node.finalize", "ok");
        recordNodeSnapshot("finalize", qualityInput, Map.of(
                RagGraphState.RESPONSE, response,
                RagGraphState.DEBUG, responseDebug,
                RagGraphState.CONTROL_DECISION, decision,
                RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                RagGraphState.TRANSITION_TRACE, decision.transitionTrace()));
        addCheckpointDebug(responseDebug, threadId);
        return response;
    }

    private RagGraphControlPolicy.Decision runSequentialRepair(QueryRequest request,
                                                               QueryResponse response,
                                                               Map<String, Object> debug,
                                                               RagGraphControlPolicy.Decision decision) {
        EvidenceRepairHandler repairHandler = repairHandlerProvider.getIfAvailable();
        if (repairHandler == null) {
            debug.put("langgraph.node.repair", "unavailable");
            return transition(decision,
                    RagGraphControlPolicy.FailureAction.FAIL_SOFT_FINALIZE,
                    "repair_unavailable",
                    "repair");
        }
        String query = request == null ? "" : request.query;
        if (query == null || query.isBlank()) {
            debug.put("langgraph.node.repair", "empty_query");
            return transition(decision,
                    RagGraphControlPolicy.FailureAction.FAIL_SOFT_FINALIZE,
                    "repair_empty_query",
                    "repair");
        }
        if (request == null || !request.useWeb) {
            debug.put("langgraph.node.repair", "skipped_web_disabled");
            return transition(decision,
                    RagGraphControlPolicy.FailureAction.FAIL_SOFT_FINALIZE,
                    "repair_web_disabled",
                    "repair");
        }
        try {
            List<Content> repairedContent = repairHandler.retrieve(buildRepairQuery(request, query));
            List<Doc> repairedDocs = toDocs(repairedContent);
            if (repairedDocs.isEmpty()) {
                debug.put("langgraph.node.repair", "empty");
                return transition(decision,
                        RagGraphControlPolicy.FailureAction.FAIL_SOFT_FINALIZE,
                        "repair_empty",
                        "repair");
            }
            if (response.results == null) {
                response.results = new ArrayList<>();
            }
            int existingCount = response.results.size();
            MergeRepairStats merge = mergeRepairedDocs(request, response, repairedDocs);
            debug.put("langgraph.node.repair", "ok");
            debug.put("langgraph.repair.added", merge.merged);
            debug.put("langgraph.repair.duplicateDropped", merge.duplicateDropped);
            debug.put("langgraph.repair.scopeDropped", merge.scopeDropped);
            debug.put("langgraph.repair.capacityDropped", merge.capacityDropped);
            debug.put("langgraph.repair.existingCount", existingCount);
            debug.put("langgraph.repair.reverified", true);
            debug.put("langgraph.repair.qualityAfter",
                    qualityGateReason(response, response.results.size(), query));
            return transition(decision,
                    RagGraphControlPolicy.FailureAction.FINALIZE,
                    "repair_ok",
                    "repair");
        } catch (Exception e) {
            propagateTerminalCancellation(e);
            String reason = "repair_error:" + ((e instanceof CancellationException || e instanceof InterruptedException)
                    ? "cancelled"
                    : e.getClass().getSimpleName());
            debug.put("langgraph.node.repair", reason);
            log.debug("[AWX2AF2][langgraph] sequential fallback repair failed reason={}", reason);
            return transition(decision,
                    RagGraphControlPolicy.FailureAction.FAIL_SOFT_FINALIZE,
                    reason,
                    "repair");
        }
    }

    private CompiledGraph<RagGraphState> graph() {
        CompiledGraph<RagGraphState> existing = compiledGraph;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (compiledGraph == null) {
                compiledGraph = buildGraph();
            }
            return compiledGraph;
        }
    }

    private CompiledGraph<RagGraphState> buildGraph() {
        try {
            StateGraph<RagGraphState> graph = new StateGraph<>(RagGraphState.SCHEMA, RagGraphState::new);
            graph.addNode("prepare", node_async(this::prepare));
            graph.addNode("decide_control", node_async(this::decideControl));
            graph.addNode("apply_policy", node_async(this::applyPolicy));
            graph.addNode("retrieve", node_async(this::retrieve));
            graph.addNode("quality_gate", node_async(this::qualityGate));
            graph.addNode("strict_verify", node_async(this::strictVerify));
            graph.addNode("repair", node_async(this::repair));
            graph.addNode("fail_soft_finalize", node_async(this::failSoftFinalize));
            graph.addNode("finalize", node_async(this::finalizeResponse));
            graph.addEdge(START, "prepare");
            graph.addEdge("prepare", "decide_control");
            graph.addEdge("decide_control", "apply_policy");
            graph.addEdge("apply_policy", "retrieve");
            graph.addEdge("retrieve", "quality_gate");
            graph.addConditionalEdges("quality_gate", edge_async(this::routeAfterQuality), Map.of(
                    "finalize", "finalize",
                    "strict_verify", "strict_verify",
                    "repair", "repair",
                    "fail_soft_finalize", "fail_soft_finalize"
            ));
            graph.addConditionalEdges("strict_verify", edge_async(this::routeAfterStrictVerify), Map.of(
                    "finalize", "finalize",
                    "repair", "repair",
                    "fail_soft_finalize", "fail_soft_finalize"
            ));
            graph.addEdge("repair", "finalize");
            graph.addEdge("fail_soft_finalize", "finalize");
            graph.addEdge("finalize", END);

            CompileConfig.Builder builder = CompileConfig.builder()
                    .graphId(GRAPH_ID)
                    .recursionLimit(properties.getMaxSteps());
            configureCheckpoint(builder, graph.getStateSerializer());
            return graph.compile(builder.build());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build LangGraph RAG graph", e);
        }
    }

    private void configureCheckpoint(CompileConfig.Builder builder, StateSerializer<RagGraphState> stateSerializer) {
        String checkpoint = properties.getCheckpoint();
        if ("postgres".equalsIgnoreCase(checkpoint)) {
            configurePostgresCheckpoint(builder, stateSerializer);
            return;
        }
        if (!"memory".equalsIgnoreCase(checkpoint)) {
            checkpointStatus = CheckpointStatus.disabled(checkpoint, false, false, "unsupported checkpoint backend");
            builder.checkpointSaver(new MemorySaver());
            return;
        }
        checkpointStatus = CheckpointStatus.memory();
        builder.checkpointSaver(new MemorySaver());
    }

    private void configurePostgresCheckpoint(CompileConfig.Builder builder, StateSerializer<RagGraphState> stateSerializer) {
        RagGraphProperties.Postgres postgres = properties.getPostgres();
        boolean hasPassword = postgres.getPassword() != null && !postgres.getPassword().isBlank();
        String disabledReason = postgresDisabledReason(postgres, hasPassword);
        if (!disabledReason.isBlank()) {
            checkpointStatus = CheckpointStatus.disabled("postgres", hasPassword, postgres.isCreateTables(), disabledReason);
            builder.checkpointSaver(new MemorySaver());
            log.info("[AWX2AF2][langgraph][checkpoint] backend=postgres enabled=false hasPassword={} createTables={} disabledReason={} threadIdHash=",
                    hasPassword, postgres.isCreateTables(), disabledReason);
            return;
        }
        try {
            builder.checkpointSaver(PostgresSaver.builder()
                    .host(postgres.getHost())
                    .port(postgres.getPort())
                    .database(postgres.getDatabase())
                    .user(postgres.getUser())
                    .password(postgres.getPassword())
                    .stateSerializer(stateSerializer)
                    .createTables(postgres.isCreateTables())
                    .dropTablesFirst(false)
                    .build());
            checkpointStatus = new CheckpointStatus("postgres", true, true, postgres.isCreateTables(), "");
        } catch (Exception e) {
            disabledReason = "init_failed:" + e.getClass().getSimpleName();
            checkpointStatus = CheckpointStatus.disabled("postgres", true, postgres.isCreateTables(), disabledReason);
            builder.checkpointSaver(new MemorySaver());
            log.warn("[AWX2AF2][langgraph][checkpoint] backend=postgres enabled=false hasPassword=true createTables={} disabledReason={} threadIdHash=",
                    postgres.isCreateTables(), disabledReason);
        }
    }

    private static String postgresDisabledReason(RagGraphProperties.Postgres postgres, boolean hasPassword) {
        if (postgres.getHost() == null || postgres.getHost().isBlank()) {
            return "missing host";
        }
        if (postgres.getDatabase() == null || postgres.getDatabase().isBlank()) {
            return "missing database";
        }
        if (postgres.getUser() == null || postgres.getUser().isBlank()) {
            return "missing user";
        }
        if (!hasPassword) {
            return "missing password";
        }
        return "";
    }

    private void addCheckpointDebug(Map<String, Object> debug, String threadId) {
        CheckpointStatus status = checkpointStatus;
        String threadIdHash = hashThreadId(threadId);
        debug.put("langgraph.checkpoint.backend", status.backend);
        debug.put("langgraph.checkpoint.enabled", status.enabled);
        debug.put("langgraph.checkpoint.hasPassword", status.hasPassword);
        debug.put("langgraph.checkpoint.createTables", status.createTables);
        debug.put("langgraph.threadIdHash", threadIdHash);
        if (!status.disabledReason.isBlank()) {
            debug.put("langgraph.checkpoint.disabledReason", status.disabledReason);
        }
        log.debug("[AWX2AF2][langgraph][checkpoint] backend={} enabled={} hasPassword={} createTables={} disabledReason={} threadIdHash={}",
                status.backend, status.enabled, status.hasPassword, status.createTables, status.disabledReason, threadIdHash);
    }

    private Map<String, Object> recordNodeSnapshot(String node,
                                                   Map<String, Object> inputContext,
                                                   Map<String, Object> updates) {
        LangGraphNodeSnapshotRecorder recorder = snapshotRecorderProvider == null
                ? null
                : snapshotRecorderProvider.getIfAvailable();
        if (recorder == null) {
            return updates;
        }
        try {
            recorder.record(node, inputContext, outputContext(inputContext, updates));
        } catch (Exception e) {
            propagateTerminalCancellation(e);
            log.debug("[AWX2AF2][langgraph][contamination] snapshot skipped node={} errorHash={} errorLength={}",
                    node, SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
        return updates;
    }

    private Map<String, Object> snapshotState(RagGraphState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (state == null) {
            return out;
        }
        putQuerySummary(out, state.query());
        out.put("planId", state.planId());
        out.put("request", requestSummary(state.request()));
        out.put("response", responseSummary(state.response()));
        out.put("trace", traceSummary(state.trace()));
        out.put("debug", safeDebug(state.debug()));
        out.put("failureReason", safeFailureReason(state.failureReason()));
        out.put("controlDecision", controlSummary(state.controlDecision()));
        out.put("safetyMode", state.safetyMode());
        out.put("retrievalPosture", state.retrievalPosture());
        out.put("failureAction", state.failureAction());
        if (!state.transitionTrace().isEmpty()) {
            out.put("transitionTrace", state.transitionTrace());
        }
        out.put("traceStore", selectedTraceStore());
        List<Map<String, Object>> docs = docsFromResponse(state.response());
        if (!docs.isEmpty()) {
            out.put("docs", docs);
        }
        return out;
    }

    private Map<String, Object> outputContext(Map<String, Object> inputContext, Map<String, Object> updates) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (inputContext != null) {
            out.putAll(inputContext);
        }
        if (updates == null || updates.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (RagGraphState.QUERY.equals(key)) {
                putQuerySummary(out, value == null ? null : String.valueOf(value));
            } else if (RagGraphState.PLAN_ID.equals(key)) {
                out.put("planId", value);
            } else if (RagGraphState.REQUEST.equals(key) && value instanceof QueryRequest request) {
                out.put("request", requestSummary(request));
            } else if (RagGraphState.RESPONSE.equals(key) && value instanceof QueryResponse response) {
                out.put("response", responseSummary(response));
                out.put("docs", docsFromResponse(response));
            } else if (RagGraphState.TRACE.equals(key) && value instanceof QueryTrace trace) {
                out.put("trace", traceSummary(trace));
            } else if (RagGraphState.DEBUG.equals(key) && value instanceof Map<?, ?> debug) {
                out.put("debug", safeDebug(debug));
            } else if (RagGraphState.FAILURE_REASON.equals(key)) {
                out.put("failureReason", safeFailureReason(value == null ? "" : String.valueOf(value)));
            } else if (RagGraphState.CONTROL_DECISION.equals(key)
                    && value instanceof RagGraphControlPolicy.Decision decision) {
                out.put("controlDecision", controlSummary(decision));
            } else if (RagGraphState.SAFETY_MODE.equals(key)) {
                out.put("safetyMode", value == null ? "" : String.valueOf(value));
            } else if (RagGraphState.RETRIEVAL_POSTURE.equals(key)) {
                out.put("retrievalPosture", value == null ? "" : String.valueOf(value));
            } else if (RagGraphState.FAILURE_ACTION.equals(key)) {
                out.put("failureAction", value == null ? "" : String.valueOf(value));
            } else if (RagGraphState.TRANSITION_TRACE.equals(key) && value instanceof List<?> rows) {
                out.put("transitionTrace", rows);
            }
        }
        out.put("traceStore", selectedTraceStore());
        return out;
    }

    private static Map<String, Object> controlSummary(RagGraphControlPolicy.Decision decision) {
        if (decision == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", decision.safetyMode().name());
        out.put("retrievalPosture", decision.retrievalPosture().name());
        out.put("failureAction", decision.failureAction().name());
        out.put("reasonCode", decision.reasonCode());
        out.put("policyRuleId", decision.policyRuleId());
        out.put("controlScore", decision.controlScore());
        out.put("transitionScore", decision.transitionScore());
        out.put("promotionEligible", decision.promotionEligible());
        out.put("promotionBlockers", decision.promotionBlockers());
        return out;
    }

    private static void putQuerySummary(Map<String, Object> out, String query) {
        out.put("queryHash", SafeRedactor.hash12(query));
        out.put("queryLength", query == null ? 0 : query.length());
    }

    private static Map<String, Object> requestSummary(QueryRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", request.planId);
        out.put("threadIdHash", hashThreadId(request.threadId));
        out.put("topK", request.topK);
        out.put("seedOnly", request.seedOnly);
        out.put("seedMode", request.seedMode);
        out.put("seedCandidateCount", request.seedCandidates == null ? 0 : request.seedCandidates.size());
        out.put("seedWebCount", request.seedWeb == null ? 0 : request.seedWeb.size());
        out.put("seedVectorCount", request.seedVector == null ? 0 : request.seedVector.size());
        out.put("useWeb", request.useWeb);
        out.put("useVector", request.useVector);
        out.put("useKg", request.useKg);
        out.put("useBm25", request.useBm25);
        out.put("enableSelfAsk", request.enableSelfAsk);
        return out;
    }

    private static Map<String, Object> responseSummary(QueryResponse response) {
        if (response == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", SafeRedactor.hashValue(response.requestId));
        out.put("planApplied", response.planApplied);
        out.put("resultCount", response.results == null ? 0 : response.results.size());
        if (response.debug != null && response.debug.containsKey("langgraph.node.retrieve")) {
            out.put("retrieveStatus", response.debug.get("langgraph.node.retrieve"));
        }
        return out;
    }

    private static Map<String, Object> traceSummary(QueryTrace trace) {
        if (trace == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seed", trace.seed == null ? 0 : trace.seed.size());
        out.put("web", trace.web == null ? 0 : trace.web.size());
        out.put("vector", trace.vector == null ? 0 : trace.vector.size());
        out.put("kg", trace.kg == null ? 0 : trace.kg.size());
        out.put("bm25", trace.bm25 == null ? 0 : trace.bm25.size());
        out.put("pool", trace.pool == null ? 0 : trace.pool.size());
        out.put("fused", trace.fused == null ? 0 : trace.fused.size());
        out.put("finalResults", trace.finalResults == null ? 0 : trace.finalResults.size());
        return out;
    }

    private static Map<String, Object> safeDebug(Map<?, ?> debug) {
        if (debug == null || debug.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<?, ?> entry : debug.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            if (key.startsWith("langgraph.")
                    || key.startsWith("rag.eval.")
                    || key.startsWith("learning.")
                    || key.startsWith("retrieval.")) {
                out.put(key, SafeRedactor.diagnosticValue(key, entry.getValue(), 500));
                if (++count >= 80) {
                    out.put("_truncated", true);
                    break;
                }
            }
        }
        return out;
    }

    private static String safeFailureReason(String reason) {
        String safe = SafeRedactor.traceLabelOrFallback(reason, "");
        return safe == null ? "" : safe;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static Map<String, Object> selectedTraceStore() {
        Map<String, Object> trace = TraceStore.getAll();
        if (trace == null || trace.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of(
                "learning.validation.contaminationSignals",
                "learning.validation.contextContaminationScore",
                "learning.feedback.vectorDecision",
                "vector.retrieval.requestedTopK",
                "vector.retrieval.poolK",
                "vector.retrieval.minScore",
                "vector.retrieval.rawMatchCount",
                "vector.retrieval.keptCount",
                "vector.retrieval.emptyReason",
                "vector.retrieval.failureClass",
                "vector.retrieval.scopeFilterRelaxed",
                "vector.retrieval.docTypeFilterRelaxed",
                "vector.scopeFilter.relaxed",
                "vector.docTypeFilter.relaxed",
                "vector.poisoning.bypass",
                "prompt.builder.contaminationFlag",
                "rag.anchor.enabled",
                "rag.anchor.anchors",
                "rag.anchor.confidence",
                "rag.anchor.driftScore",
                "rag.anchor.acceptedCandidateCount",
                "rag.anchor.rejectedCandidateCount",
                "rag.anchor.reason",
                "rag.budget.suggested.webTopK",
                "rag.budget.suggested.vectorTopK",
                "rag.budget.suggested.kgTopK",
                "rag.budget.applied.queryBurstCount",
                "rag.budget.suggested.enableHypernova",
                "rag.budget.suggested.enableMassiveExpansion",
                "rag.budget.reason",
                "rag.metrics.anchorPrecisionProxy",
                "rag.metrics.textureHitRate",
                "rag.metrics.burstReductionRate",
                "rag.metrics.kgRetention",
                "rag.fusion.score.mean.web",
                "rag.fusion.score.mean.vector",
                "rag.fusion.score.mean.kg",
                "rag.metrics.offlinePredictionUsed",
                "rag.offlineTexture.loadedSnapshots",
                "rag.offlineTexture.matchedSnapshots",
                "rag.offlineTexture.staleSnapshots",
                "rag.offlineTexture.hitRate",
                "rag.offlineTexture.coldStart",
                "rag.offlineTexture.stale",
                "rag.offlineTexture.onlineSearchNeeded",
                "rag.offlineTexture.reason",
                "rag.offlineTexture.write.status",
                "langgraph.invoke.source",
                "langgraph.invoke.fallbackTriggered",
                "langgraph.invoke.trigger",
                "langgraph.invoke.failureClass",
                "langgraph.invoke.threadIdHash",
                "langgraph.invoke.queryHash",
                "langgraph.invoke.primaryCandidate",
                "langgraph.invoke.fallback.count",
                "langgraph.contamination.scenario",
                "retrieval.kg.brainState.status",
                "retrieval.kg.brainState.enabled",
                "retrieval.kg.brainState.reason",
                "retrieval.kg.brainState.matchedEntityCount",
                "retrieval.kg.brainState.inferredRelationCount",
                "retrieval.kg.brainState.addedCount",
                "retrieval.kg.brainState.fallbackUsed",
                "retrieval.kg.brainState.portMappingCount",
                "retrieval.kg.brainState.portMappingHashes",
                "retrieval.kg.brainState.queryAnchorMap.applied",
                "retrieval.kg.brainState.queryAnchorMap.seedCount",
                "retrieval.kg.brainState.queryAnchorMap.seedHashes",
                "retrieval.kg.brainState.queryAnchorMap.reason",
                "retrieval.kg.brainState.disabledReason",
                "retrieval.kg.brainState.failureClass",
                "retrieval.kg.brainState.tookMs",
                "retrieval.kg.brainState.queryHash12")) {
            if (trace.containsKey(key)) {
                out.put(key, key.startsWith("langgraph.invoke.")
                        ? safeInvokeTraceValue(key, trace.get(key))
                        : key.startsWith("retrieval.kg.brainState.")
                        ? safeBrainStateTraceValue(key, trace.get(key))
                        : SafeRedactor.diagnosticValue(key, trace.get(key), 500));
            }
        }
        copyAllowedTraceMap(trace, out, "rag.eval.kgAxis", KG_AXIS_TRACE_KEYS);
        copyAllowedTraceMap(trace, out, "rag.eval.scorecard", SCORECARD_TRACE_KEYS);
        copyThresholdBreakSummary(trace, out);
        Object events = trace.get("orch.events.v1");
        if (events instanceof List<?> list) {
            out.put("orch.events.v1.count", list.size());
        }
        Object brainEvents = trace.get("retrieval.kg.brainState.events");
        if (brainEvents instanceof List<?> list) {
            out.put("retrieval.kg.brainState.events.count", list.size());
        }
        return out;
    }

    private static Object safeBrainStateTraceValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof List<?> list && (key.endsWith(".seedHashes") || key.endsWith(".portMappingHashes"))) {
            return list.stream()
                    .map(item -> {
                        String hex = String.valueOf(item).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
                        return hex.length() < 12 ? "" : hex.substring(0, 12);
                    })
                    .filter(hash -> !hash.isBlank())
                    .distinct()
                    .limit(25)
                    .toList();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return "";
        }
        if (key.endsWith("queryHash12")) {
            String hex = text.toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
            return hex.substring(0, Math.min(12, hex.length()));
        }
        String safe = text.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return safe.length() <= 96 ? safe : safe.substring(0, 96);
    }

    private static Object safeInvokeTraceValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return "";
        }
        if (key.endsWith(".threadIdHash") || key.endsWith(".queryHash")) {
            String hex = text.replaceAll("[^a-fA-F0-9]", "");
            return hex.substring(0, Math.min(32, hex.length()));
        }
        String safe = text.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return safe.length() <= 96 ? safe : safe.substring(0, 96);
    }

    private static void copyAllowedTraceMap(Map<String, Object> trace,
                                            Map<String, Object> out,
                                            String key,
                                            List<String> allowedKeys) {
        Object raw = trace.get(key);
        if (!(raw instanceof Map<?, ?> map) || allowedKeys == null || allowedKeys.isEmpty()) {
            return;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String allowedKey : allowedKeys) {
            if (allowedKey == null || !map.containsKey(allowedKey)) {
                continue;
            }
            safe.put(allowedKey, SafeRedactor.diagnosticValue(key + "." + allowedKey, map.get(allowedKey), 300));
        }
        if (!safe.isEmpty()) {
            out.put(key, safe);
        }
    }

    private static void copyThresholdBreakSummary(Map<String, Object> trace, Map<String, Object> out) {
        Object raw = trace.get("rag.eval.thresholdBreaks");
        if (!(raw instanceof Iterable<?> rows)) {
            return;
        }
        List<Map<String, Object>> safeRows = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            for (String key : List.of("label", "reasonCode", "scope", "metric", "status")) {
                if (map.containsKey(key)) {
                    safe.put(key, SafeRedactor.diagnosticValue("rag.eval.thresholdBreaks." + key, map.get(key), 160));
                }
            }
            if (!safe.isEmpty()) {
                safeRows.add(safe);
            }
        }
        if (!safeRows.isEmpty()) {
            out.put("rag.eval.thresholdBreaks", safeRows);
        }
    }

    private static List<Map<String, Object>> docsFromResponse(QueryResponse response) {
        if (response == null || response.results == null || response.results.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int limit = Math.min(response.results.size(), 20);
        for (int i = 0; i < limit; i++) {
            Doc doc = response.results.get(i);
            if (doc == null) {
                continue;
            }
            out.add(docSummary(doc, i + 1));
        }
        return out;
    }

    private static Map<String, Object> docSummary(Doc doc, int fallbackRank) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idHash", SafeRedactor.hash12(doc.id));
        out.put("title", excerpt(doc.title, 160));
        out.put("source", doc.source == null ? "UNKNOWN" : doc.source);
        out.put("score", doc.score);
        out.put("rank", doc.rank > 0 ? doc.rank : fallbackRank);
        out.put("excerpt", excerpt(doc.snippet, 220));
        out.put("textHash12", SafeRedactor.hash12(doc.snippet));
        if (doc.meta != null && !doc.meta.isEmpty()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            for (String key : List.of("url", "fixture", "ageDays", "stale", "memoryScope", "langgraph.repair")) {
                if (doc.meta.containsKey(key)) {
                    meta.put(key, SafeRedactor.diagnosticValue(key, doc.meta.get(key), 300));
                }
            }
            out.put("meta", meta);
        }
        return out;
    }

    private static String excerpt(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String redacted = SafeRedactor.redact(value).replace('\n', ' ').replace('\r', ' ').trim();
        int max = Math.max(32, maxLen);
        return redacted.length() <= max ? redacted : redacted.substring(0, max) + "...";
    }

    private Map<String, Object> prepare(RagGraphState state) {
        Map<String, Object> nodeInput = snapshotState(state);
        QueryRequest request = state.request();
        if (request == null) {
            request = new QueryRequest();
            request.query = state.query();
            if (request.planId == null || request.planId.isBlank()) {
                request.planId = state.planId();
            }
        }
        Map<String, Object> debug = state.debug();
        debug.put("langgraph.node.prepare", "ok");
        return recordNodeSnapshot("prepare", nodeInput, updates(
                RagGraphState.REQUEST, request,
                RagGraphState.QUERY, request.query,
                RagGraphState.PLAN_ID, request.planId,
                RagGraphState.DEBUG, debug
        ));
    }

    private Map<String, Object> decideControl(RagGraphState state) {
        Map<String, Object> nodeInput = snapshotState(state);
        Map<String, Object> debug = state.debug();
        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.decide(state.request(), debug);
        debug.put("langgraph.node.decide_control", "ok");
        RagGraphControlPolicy.writeDebug(debug, decision);
        return recordNodeSnapshot("decide_control", nodeInput, controlUpdates(decision, debug));
    }

    private Map<String, Object> applyPolicy(RagGraphState state) {
        Map<String, Object> nodeInput = snapshotState(state);
        Map<String, Object> debug = state.debug();
        RagGraphControlPolicy.Decision decision = state.controlDecision();
        if (decision == null) {
            decision = RagGraphControlPolicy.decide(state.request(), debug);
        }
        QueryRequest request = RagGraphControlPolicy.applyPolicy(decision, state.request());
        debug.put("langgraph.node.apply_policy", "ok");
        RagGraphControlPolicy.writeDebug(debug, decision);
        return recordNodeSnapshot("apply_policy", nodeInput, updates(
                RagGraphState.REQUEST, request,
                RagGraphState.DEBUG, debug,
                RagGraphState.CONTROL_DECISION, decision,
                RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                RagGraphState.TRANSITION_TRACE, decision.transitionTrace()
        ));
    }

    private Map<String, Object> retrieve(RagGraphState state) {
        Map<String, Object> nodeInput = snapshotState(state);
        Map<String, Object> debug = state.debug();
        QueryRequest request = state.request();
        try {
            QueryTrace trace = orchestrator.queryWithTrace(request);
            QueryResponse response = trace != null ? trace.response : null;
            if (response == null) {
                response = emptyResponse(request, "retrieve_no_response");
            }
            ensureDebug(response).putAll(debug);
            ensureDebug(response).put("langgraph.node.retrieve", "ok");
            return recordNodeSnapshot("retrieve", nodeInput, updates(
                    RagGraphState.TRACE, trace,
                    RagGraphState.RESPONSE, response,
                    RagGraphState.DEBUG, ensureDebug(response)
            ));
        } catch (Exception e) {
            propagateTerminalCancellation(e);
            String reason = "retrieve_error:" + ((e instanceof CancellationException || e instanceof InterruptedException)
                    ? "cancelled"
                    : e.getClass().getSimpleName());
            debug.put("langgraph.node.retrieve", reason);
            log.debug("[AWX2AF2][langgraph] retrieve fail-soft candidate: {}", reason);
            return recordNodeSnapshot("retrieve", nodeInput, updates(
                    RagGraphState.FAILURE_REASON, reason,
                    RagGraphState.DEBUG, debug
            ));
        }
    }

    private Map<String, Object> qualityGate(RagGraphState state) {
        Map<String, Object> nodeInput = snapshotState(state);
        Map<String, Object> debug = state.debug();
        QueryResponse response = state.response();
        int resultCount = response == null || response.results == null ? 0 : response.results.size();
        String qualityReason = qualityGateReason(response, resultCount,
                state.request() == null ? "" : state.request().query);
        debug.put("langgraph.node.quality_gate.resultCount", resultCount);
        debug.put("langgraph.node.quality_gate.reason", qualityReason);
        debug.put("langgraph.node.quality_gate.degraded", !qualityReason.isBlank());
        String reason = !state.failureReason().isBlank() ? state.failureReason() : qualityReason;
        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.afterQuality(
                state.controlDecision(),
                state.request(),
                response,
                state.failureReason(),
                qualityReason,
                resultCount);
        RagGraphControlPolicy.writeDebug(debug, decision);
        String failureReason = decision.failureAction() == RagGraphControlPolicy.FailureAction.FINALIZE
                ? ""
                : decision.reasonCode();
        return recordNodeSnapshot("quality_gate", nodeInput, updates(
                RagGraphState.FAILURE_REASON, failureReason.isBlank() ? reason : failureReason,
                RagGraphState.DEBUG, debug,
                RagGraphState.CONTROL_DECISION, decision,
                RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                RagGraphState.TRANSITION_TRACE, decision.transitionTrace()
        ));
    }

    private static String qualityGateReason(QueryResponse response, int resultCount, String query) {
        if (resultCount == 0) {
            return "empty_results";
        }
        Map<String, Object> debug = response == null || response.debug == null ? Map.of() : response.debug;
        String scorecardReason = firstRepairLabel(scorecardLabels(debug.get("rag.eval.scorecard")));
        if (!scorecardReason.isBlank()) {
            return scorecardReason;
        }
        String kgAxisReason = firstRepairLabel(kgAxisLabels(debug.get("rag.eval.kgAxis")));
        if (!kgAxisReason.isBlank()) {
            return kgAxisReason;
        }
        return firstRepairLabel(thresholdBreakLabels(debug.get("rag.eval.thresholdBreaks")),
                com.example.lms.service.rag.offline.OfflineTextureSnapshotLoader.looksFreshnessQuery(query));
    }

    private static List<String> scorecardLabels(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        addLabel(labels, map.get("reasonCode"));
        Object rawLabels = map.get("thresholdLabels");
        if (rawLabels instanceof Iterable<?> iterable) {
            for (Object label : iterable) {
                addLabel(labels, label);
            }
        } else {
            addLabel(labels, rawLabels);
        }
        return labels;
    }

    private static List<String> kgAxisLabels(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        Object rawSignals = map.get("signals");
        if (rawSignals instanceof Iterable<?> iterable) {
            for (Object signal : iterable) {
                addLabel(labels, signal);
            }
        } else {
            addLabel(labels, rawSignals);
        }
        String status = map.get("status") == null ? "" : String.valueOf(map.get("status")).trim();
        if ("retrieved_dropped".equals(status)) {
            addLabel(labels, "kg_final_drop");
        } else if ("empty".equals(status)) {
            addLabel(labels, "kg_starvation");
        }
        if (Boolean.TRUE.equals(map.get("graphOpportunity"))) {
            double kgPoolCount = firstNumber(map, "kgPoolCount", "retrievedCount");
            double retention = firstNumber(map, "kgFinalRetention", "finalRetention");
            addLabel(labels, kgPoolCount > 0.0d && retention <= 0.0d ? "kg_final_drop" : "kg_starvation");
        }
        return labels;
    }

    private static double firstNumber(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return 0.0d;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                double parsed = number.doubleValue();
                if (Double.isFinite(parsed)) {
                    return parsed;
                }
                TraceStore.put("langgraph.thresholdBreak.suppressed.numberParse", true);
                TraceStore.put("langgraph.thresholdBreak.suppressed.numberParse.errorType", "invalid_number");
                continue;
            }
            if (value != null) {
                try {
                    double parsed = Double.parseDouble(String.valueOf(value));
                    if (Double.isFinite(parsed)) {
                        return parsed;
                    }
                    TraceStore.put("langgraph.thresholdBreak.suppressed.numberParse", true);
                    TraceStore.put("langgraph.thresholdBreak.suppressed.numberParse.errorType", "invalid_number");
                } catch (NumberFormatException ignore) {
                    TraceStore.put("langgraph.thresholdBreak.suppressed.numberParse", true);
                    TraceStore.put("langgraph.thresholdBreak.suppressed.numberParse.errorType", "invalid_number");
                }
            }
        }
        return 0.0d;
    }

    private static List<String> thresholdBreakLabels(Object raw) {
        if (!(raw instanceof Iterable<?> rows)) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                addLabel(labels, map.get("label"));
                addLabel(labels, map.get("reasonCode"));
            } else {
                addLabel(labels, row);
            }
        }
        return labels;
    }

    private static String firstRepairLabel(List<String> labels) {
        return firstRepairLabel(labels, true);
    }

    private static String firstRepairLabel(List<String> labels, boolean allowOfflineTextureStale) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        for (String target : QUALITY_REPAIR_LABELS) {
            if ("offline_texture_stale".equals(target) && !allowOfflineTextureStale) {
                continue;
            }
            for (String label : labels) {
                if (target.equals(label)) {
                    return target;
                }
            }
        }
        return "";
    }

    private static void addLabel(List<String> labels, Object raw) {
        if (labels == null || raw == null) {
            return;
        }
        String label = String.valueOf(raw).trim();
        if (!label.isBlank()) {
            labels.add(label);
        }
    }

    private String routeAfterQuality(RagGraphState state) {
        return RagGraphControlPolicy.route(state.controlDecision());
    }

    private String routeAfterStrictVerify(RagGraphState state) {
        return RagGraphControlPolicy.route(state.controlDecision());
    }

    private Map<String, Object> strictVerify(RagGraphState state) {
        Map<String, Object> nodeInput = snapshotState(state);
        Map<String, Object> debug = state.debug();
        QueryResponse response = state.response();
        RagGraphControlPolicy.Decision decision = RagGraphControlPolicy.afterStrictVerify(
                state.controlDecision(),
                response);
        boolean passed = decision.failureAction() == RagGraphControlPolicy.FailureAction.FINALIZE;
        debug.put("langgraph.node.strict_verify", "ok");
        debug.put("langgraph.strictVerify.passed", passed);
        debug.put("langgraph.strictVerify.reasonCode", decision.reasonCode());
        debug.put("langgraph.strictVerify.resultCount",
                response == null || response.results == null ? 0 : response.results.size());
        RagGraphControlPolicy.writeDebug(debug, decision);
        return recordNodeSnapshot("strict_verify", nodeInput, updates(
                RagGraphState.FAILURE_REASON, passed ? "" : decision.reasonCode(),
                RagGraphState.DEBUG, debug,
                RagGraphState.CONTROL_DECISION, decision,
                RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                RagGraphState.TRANSITION_TRACE, decision.transitionTrace()
        ));
    }

    private Map<String, Object> repair(RagGraphState state) {
        Map<String, Object> nodeInput = snapshotState(state);
        Map<String, Object> debug = state.debug();
        QueryRequest request = state.request();
        EvidenceRepairHandler repairHandler = repairHandlerProvider.getIfAvailable();
        if (repairHandler == null) {
            debug.put("langgraph.node.repair", "unavailable");
            return repairFailSoft(state, request, nodeInput, debug, "repair_unavailable");
        }
        String query = request == null ? "" : request.query;
        if (query == null || query.isBlank()) {
            debug.put("langgraph.node.repair", "empty_query");
            return repairFailSoft(state, request, nodeInput, debug, "repair_empty_query");
        }
        if (!request.useWeb) {
            // Evidence repair is web-backed; a web-disabled request must not
            // regain external access through this stage.
            debug.put("langgraph.node.repair", "skipped_web_disabled");
            return repairFailSoft(state, request, nodeInput, debug, "repair_web_disabled");
        }
        try {
            List<Content> repairedContent = repairHandler.retrieve(buildRepairQuery(request, query));
            List<Doc> repairedDocs = toDocs(repairedContent);
            if (repairedDocs.isEmpty()) {
                debug.put("langgraph.node.repair", "empty");
                return repairFailSoft(state, request, nodeInput, debug, "repair_empty");
            }
            QueryResponse response = state.response();
            if (response == null) {
                response = emptyResponse(request, "repair_created_response");
            }
            if (response.results == null) {
                response.results = new ArrayList<>();
            }
            int existingCount = response.results.size();
            MergeRepairStats merge = mergeRepairedDocs(request, response, repairedDocs);
            ensureDebug(response).putAll(debug);
            ensureDebug(response).put("langgraph.node.repair", "ok");
            ensureDebug(response).put("langgraph.repair.added", merge.merged);
            ensureDebug(response).put("langgraph.repair.duplicateDropped", merge.duplicateDropped);
            ensureDebug(response).put("langgraph.repair.scopeDropped", merge.scopeDropped);
            ensureDebug(response).put("langgraph.repair.capacityDropped", merge.capacityDropped);
            ensureDebug(response).put("langgraph.repair.existingCount", existingCount);
            ensureDebug(response).put("langgraph.repair.reverified", true);
            ensureDebug(response).put("langgraph.repair.qualityAfter",
                    qualityGateReason(response, response.results.size(), query));
            RagGraphControlPolicy.Decision decision = transition(state.controlDecision(),
                    RagGraphControlPolicy.FailureAction.FINALIZE,
                    "repair_ok",
                    "repair");
            RagGraphControlPolicy.writeDebug(ensureDebug(response), decision);
            return recordNodeSnapshot("repair", nodeInput, updates(
                    RagGraphState.RESPONSE, response,
                    RagGraphState.FAILURE_REASON, "",
                    RagGraphState.DEBUG, ensureDebug(response),
                    RagGraphState.CONTROL_DECISION, decision,
                    RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                    RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                    RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                    RagGraphState.TRANSITION_TRACE, decision.transitionTrace()
            ));
        } catch (Exception e) {
            propagateTerminalCancellation(e);
            String reason = "repair_error:" + ((e instanceof CancellationException || e instanceof InterruptedException)
                    ? "cancelled"
                    : e.getClass().getSimpleName());
            debug.put("langgraph.node.repair", reason);
            log.debug("[AWX2AF2][langgraph] repair fail-soft candidate: {}", reason);
            return repairFailSoft(state, request, nodeInput, debug, reason);
        }
    }

    private Map<String, Object> repairFailSoft(RagGraphState state,
                                               QueryRequest request,
                                               Map<String, Object> nodeInput,
                                               Map<String, Object> debug,
                                               String reason) {
        RagGraphControlPolicy.Decision decision = transition(state.controlDecision(),
                RagGraphControlPolicy.FailureAction.FAIL_SOFT_FINALIZE,
                reason,
                "repair");
        RagGraphControlPolicy.writeDebug(debug, decision);
        QueryResponse response = responseForRepairFailure(state, request, reason, debug);
        return recordNodeSnapshot("repair", nodeInput, updates(
                RagGraphState.RESPONSE, response,
                RagGraphState.FAILURE_REASON, reason,
                RagGraphState.DEBUG, ensureDebug(response),
                RagGraphState.CONTROL_DECISION, decision,
                RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                RagGraphState.TRANSITION_TRACE, decision.transitionTrace()
        ));
    }

    /**
     * Repair evidence is web-backed; the scoped query carries the request's own
     * web/whitelist/doc-type scope and remaining time budget so repair can never
     * widen the permissions of the original request.
     */
    private static Query buildRepairQuery(QueryRequest request, String query) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("allowWeb", request != null && request.useWeb);
        meta.put("allowRag", request == null || request.useVector || request.useKg);
        meta.put("webTopK", boundedRepairTopK(request));
        meta.put(VectorMetaKeys.META_ALLOWED_DOC_TYPES, "KB,MEMORY,LEGACY");
        meta.put("rag.repair.scope", "bounded");
        if (request != null && request.whitelistOnly) {
            meta.put("officialSourcesOnly", true);
        }
        TimeBudget budget = TimeBudgetContext.get();
        if (budget != null && !budget.expired()) {
            meta.put("webBudgetMs", Math.min(budget.remainingMillis(), 3000L));
        }
        return QueryUtils.buildQuery(query, meta);
    }

    private static int boundedRepairTopK(QueryRequest request) {
        int cap = request != null && request.topK > 0 ? request.topK : 3;
        return Math.min(Math.max(cap, 1), 5);
    }

    private static final class MergeRepairStats {
        int merged;
        int duplicateDropped;
        int scopeDropped;
        int capacityDropped;
    }

    /**
     * Merge repaired docs under the original request's policy: dedup against
     * existing results (url/title/normalized text), enforce whitelist-only scope,
     * keep repaired evidence below already-retrieved scores, and cap the merged
     * list so a full pool is not grown by unverified candidates.
     */
    private MergeRepairStats mergeRepairedDocs(QueryRequest request,
                                               QueryResponse response,
                                               List<Doc> repairedDocs) {
        MergeRepairStats stats = new MergeRepairStats();
        if (response == null || repairedDocs == null || repairedDocs.isEmpty()) {
            return stats;
        }
        if (response.results == null) {
            response.results = new ArrayList<>();
        }
        Set<String> seen = new HashSet<>();
        for (Doc existing : response.results) {
            collectDocKeys(seen, existing);
        }
        int existingCount = response.results.size();
        double existingFloor = Double.POSITIVE_INFINITY;
        for (Doc existing : response.results) {
            if (existing != null) {
                existingFloor = Math.min(existingFloor, existing.score);
            }
        }
        List<Doc> accepted = new ArrayList<>();
        for (Doc doc : repairedDocs) {
            if (doc == null) {
                continue;
            }
            Set<String> keys = docIdentityKeys(doc);
            boolean duplicate = false;
            for (String key : keys) {
                if (seen.contains(key)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                stats.duplicateDropped++;
                continue;
            }
            if (request != null && request.whitelistOnly) {
                String url = metaString(doc, VectorMetaKeys.META_SOURCE_URL);
                boolean allowed = url != null && domainWhitelist != null && domainWhitelist.isOfficial(url);
                if (!allowed) {
                    stats.scopeDropped++;
                    continue;
                }
            }
            if (existingCount > 0) {
                double floorScore = existingFloor == Double.POSITIVE_INFINITY ? 0.0d : existingFloor;
                doc.score = Math.min(doc.score, Math.max(0.0d, floorScore) * 0.9d);
            }
            if (doc.meta == null) {
                doc.meta = new LinkedHashMap<>();
            }
            doc.meta.put("repair.unverified", true);
            accepted.add(doc);
            seen.addAll(keys);
        }
        int capacity = request != null && request.topK > 0
                ? Math.max(request.topK, existingCount)
                : Integer.MAX_VALUE;
        int room = Math.max(0, capacity - existingCount);
        List<Doc> admitted = accepted.size() <= room ? accepted : new ArrayList<>(accepted.subList(0, room));
        stats.capacityDropped = accepted.size() - admitted.size();
        response.results.addAll(admitted);
        response.results.sort(Comparator.comparingDouble((Doc d) -> d == null ? 0.0d : d.score).reversed());
        for (int i = 0; i < response.results.size(); i++) {
            Doc d = response.results.get(i);
            if (d != null) {
                d.rank = i + 1;
            }
        }
        stats.merged = admitted.size();
        return stats;
    }

    private static void collectDocKeys(Set<String> sink, Doc doc) {
        sink.addAll(docIdentityKeys(doc));
    }

    private static Set<String> docIdentityKeys(Doc doc) {
        Set<String> keys = new HashSet<>();
        if (doc == null) {
            return keys;
        }
        String url = metaString(doc, VectorMetaKeys.META_SOURCE_URL);
        if (url != null) {
            keys.add("url:" + normalizeDocKey(url));
        }
        if (doc.snippet != null) {
            String norm = doc.snippet.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (!norm.isBlank()) {
                keys.add("text:" + norm);
            }
        }
        if (doc.title != null && !doc.title.isBlank() && !"repair evidence".equals(doc.title)) {
            keys.add("title:" + doc.title.trim().toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    private static String normalizeDocKey(String url) {
        String out = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int cut = out.indexOf('#');
        if (cut >= 0) {
            out = out.substring(0, cut);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String metaString(Doc doc, String key) {
        if (doc == null || doc.meta == null || key == null) {
            return null;
        }
        Object value = doc.meta.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static QueryResponse responseForRepairFailure(RagGraphState state,
                                                          QueryRequest request,
                                                          String reason,
                                                          Map<String, Object> debug) {
        QueryResponse response = state.response();
        if (response == null) {
            response = emptyResponse(request, reason);
        }
        ensureDebug(response).putAll(debug);
        sanitizePublicEvalDebug(ensureDebug(response));
        return response;
    }

    private Map<String, Object> finalizeResponse(RagGraphState state) {
        Map<String, Object> nodeInput = snapshotState(state);
        QueryResponse response = state.response();
        if (response == null) {
            response = emptyResponse(state.request(), state.failureReason());
        }
        ensureDebug(response).putAll(state.debug());
        sanitizePublicEvalDebug(ensureDebug(response));
        ensureDebug(response).put("langgraph.node.finalize", "ok");
        return recordNodeSnapshot("finalize", nodeInput, updates(RagGraphState.RESPONSE, response));
    }

    private Map<String, Object> failSoftFinalize(RagGraphState state) {
        Map<String, Object> nodeInput = snapshotState(state);
        Map<String, Object> debug = state.debug();
        String reason = state.failureReason().isBlank() ? "fail_soft_finalize" : state.failureReason();
        debug.put("langgraph.node.fail_soft_finalize", "ok");
        debug.put("langgraph.failSoft.reasonCode", safeFailureReason(reason));
        RagGraphControlPolicy.Decision decision = transition(state.controlDecision(),
                RagGraphControlPolicy.FailureAction.FAIL_SOFT_FINALIZE,
                reason,
                "fail_soft_finalize");
        RagGraphControlPolicy.writeDebug(debug, decision);
        QueryResponse response = responseForRepairFailure(state, state.request(), reason, debug);
        return recordNodeSnapshot("fail_soft_finalize", nodeInput, updates(
                RagGraphState.RESPONSE, response,
                RagGraphState.FAILURE_REASON, reason,
                RagGraphState.DEBUG, ensureDebug(response),
                RagGraphState.CONTROL_DECISION, decision,
                RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                RagGraphState.TRANSITION_TRACE, decision.transitionTrace()
        ));
    }

    private static QueryResponse emptyResponse(QueryRequest request, String reason) {
        QueryResponse response = new QueryResponse();
        response.requestId = UUID.randomUUID().toString();
        response.planApplied = request == null ? null : request.planId;
        response.debug.put("langgraph.emptyReason", safeFailureReason(reason));
        return response;
    }

    private static Map<String, Object> ensureDebug(QueryResponse response) {
        if (response.debug == null) {
            response.debug = new LinkedHashMap<>();
        }
        return response.debug;
    }

    private static void sanitizePublicEvalDebug(Map<String, Object> debug) {
        if (debug == null || debug.isEmpty()) {
            return;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        copyAllowedTraceMap(debug, safe, "rag.eval.kgAxis", KG_AXIS_TRACE_KEYS);
        copyAllowedTraceMap(debug, safe, "rag.eval.scorecard", SCORECARD_TRACE_KEYS);
        copyThresholdBreakSummary(debug, safe);
        for (String key : List.of("rag.eval.kgAxis", "rag.eval.scorecard", "rag.eval.thresholdBreaks")) {
            if (safe.containsKey(key)) {
                debug.put(key, safe.get(key));
            } else {
                debug.remove(key);
            }
        }
    }

    private static Map<String, Object> updates(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                out.put(String.valueOf(pairs[i]), value);
            }
        }
        return out;
    }

    private static Map<String, Object> controlUpdates(RagGraphControlPolicy.Decision decision,
                                                      Map<String, Object> debug) {
        if (decision == null) {
            return updates(RagGraphState.DEBUG, debug);
        }
        return updates(
                RagGraphState.DEBUG, debug,
                RagGraphState.CONTROL_DECISION, decision,
                RagGraphState.SAFETY_MODE, decision.safetyMode().name(),
                RagGraphState.RETRIEVAL_POSTURE, decision.retrievalPosture().name(),
                RagGraphState.FAILURE_ACTION, decision.failureAction().name(),
                RagGraphState.TRANSITION_TRACE, decision.transitionTrace());
    }

    private static RagGraphControlPolicy.Decision transition(RagGraphControlPolicy.Decision current,
                                                            RagGraphControlPolicy.FailureAction action,
                                                            String reason,
                                                            String node) {
        RagGraphControlPolicy.Decision base = current == null
                ? new RagGraphControlPolicy.Decision(
                RagGraphControlPolicy.SafetyMode.NORMAL,
                RagGraphControlPolicy.RetrievalPosture.NORMAL,
                RagGraphControlPolicy.FailureAction.FINALIZE,
                "normal",
                List.of())
                : current;
        return base.withAction(action, reason, node);
    }

    private static List<Doc> toDocs(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<Doc> docs = new ArrayList<>();
        int rank = 1;
        for (Content content : contents) {
            String text = textOf(content);
            if (text.isBlank()) {
                continue;
            }
            var segmentMeta = content.textSegment() == null ? null : content.textSegment().metadata();
            String url = segmentMeta == null ? null : segmentMeta.getString(VectorMetaKeys.META_SOURCE_URL);
            String srcMeta = segmentMeta == null ? null : segmentMeta.getString("source");
            String title = segmentMeta == null ? null : segmentMeta.getString("title");
            Doc doc = new Doc();
            doc.id = "repair:" + rank + ":" + Integer.toHexString(text.hashCode());
            doc.title = (title != null && !title.isBlank()) ? title.trim() : "repair evidence";
            doc.snippet = text;
            doc.source = "REPAIR";
            doc.score = 1.0d / rank;
            doc.rank = rank;
            doc.meta = new LinkedHashMap<>();
            doc.meta.put("langgraph.repair", true);
            if (url != null && !url.isBlank()) {
                doc.meta.put(VectorMetaKeys.META_SOURCE_URL, url.trim());
            }
            if (srcMeta != null && !srcMeta.isBlank()) {
                doc.meta.put("source", srcMeta.trim());
            }
            docs.add(doc);
            rank++;
        }
        return docs;
    }

    private static String textOf(Content content) {
        if (content == null) {
            return "";
        }
        var textSegment = content.textSegment();
        return textSegment == null ? content.toString() : textSegment.text();
    }

    private static void propagateTerminalCancellation(Throwable failure) {
        CancellationException cancellation = null;
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof InterruptedException interrupted) {
                throw terminalInterruption(interrupted);
            }
            if (cancellation == null && cursor instanceof CancellationException candidate) {
                cancellation = candidate;
            }
            Throwable cause = cursor.getCause();
            if (cause == cursor) {
                break;
            }
            cursor = cause;
        }
        if (cancellation != null) {
            throw cancellation;
        }
    }

    private static CancellationException terminalInterruption(InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        CancellationException cancellation = new CancellationException("operation interrupted");
        cancellation.initCause(interrupted);
        return cancellation;
    }

    static String resolveThreadId(QueryRequest request) {
        String fromRequest = sanitizeThreadId(request == null ? null : request.threadId);
        if (!fromRequest.isBlank()) {
            return fromRequest;
        }
        String fromSid = sanitizeThreadId(MDC.get("sid"));
        if (!fromSid.isBlank()) {
            return fromSid;
        }
        String fromSession = sanitizeThreadId(MDC.get("sessionId"));
        if (!fromSession.isBlank()) {
            return fromSession;
        }
        return UUID.randomUUID().toString();
    }

    static String hashThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return "";
        }
        return DigestUtils.sha256Hex(threadId).substring(0, 12);
    }

    private static String sanitizeThreadId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String sanitized = raw.trim().replaceAll("[^A-Za-z0-9._:-]", "_");
        return sanitized.length() <= 128 ? sanitized : sanitized.substring(0, 128);
    }

    private record InvokeFallbackTrigger(
            String trigger,
            String failureClass,
            String source,
            String exception) {

        private static InvokeFallbackTrigger of(String trigger, String failureClass, Exception cause) {
            String safeTrigger = trigger == null || trigger.isBlank() ? "graph_invoke_unknown" : trigger.trim();
            String safeFailureClass = failureClass == null || failureClass.isBlank()
                    ? "langgraph-invoke-error"
                    : failureClass.trim();
            String exception = cause == null ? "" : ((cause instanceof CancellationException || cause instanceof InterruptedException)
                    ? "cancelled"
                    : cause.getClass().getSimpleName());
            return new InvokeFallbackTrigger(safeTrigger, safeFailureClass, "compiled_graph_invoke", exception);
        }

        private Map<String, Object> toTraceEvent(String threadIdHash, String queryHash) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", source);
            out.put("trigger", trigger);
            out.put("failureClass", failureClass);
            out.put("threadIdHash", threadIdHash);
            out.put("queryHash", queryHash);
            if (!exception.isBlank()) {
                out.put("exception", exception);
            }
            return Map.copyOf(out);
        }
    }

    private static final class CheckpointStatus {
        private final String backend;
        private final boolean enabled;
        private final boolean hasPassword;
        private final boolean createTables;
        private final String disabledReason;

        private CheckpointStatus(String backend, boolean enabled, boolean hasPassword, boolean createTables,
                                 String disabledReason) {
            this.backend = backend;
            this.enabled = enabled;
            this.hasPassword = hasPassword;
            this.createTables = createTables;
            this.disabledReason = disabledReason == null ? "" : disabledReason;
        }

        private static CheckpointStatus memory() {
            return new CheckpointStatus("memory", true, false, false, "");
        }

        private static CheckpointStatus disabled(String backend, boolean hasPassword, boolean createTables,
                                                 String disabledReason) {
            return new CheckpointStatus(backend, false, hasPassword, createTables, disabledReason);
        }
    }
}

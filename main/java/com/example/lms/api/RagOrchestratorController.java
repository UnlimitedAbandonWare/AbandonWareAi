package com.example.lms.api;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.search.DeadlineProbe;
import com.example.lms.search.RequestTrace;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/rag")
public class RagOrchestratorController {

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    private final RagOrchestratorFacade orchestrator;
    private final ExecutorService endpointExecutor;

    @Autowired(required = false)
    private PublicRequestBudgetGuard publicRequestBudgetGuard = new PublicRequestBudgetGuard();

    public RagOrchestratorController(RagOrchestratorFacade orchestrator) {
        this(orchestrator, null);
    }

    @Autowired
    public RagOrchestratorController(
            RagOrchestratorFacade orchestrator,
            @Qualifier("ragEndpointExecutor") ExecutorService endpointExecutor) {
        this.orchestrator = orchestrator;
        this.endpointExecutor = endpointExecutor;
    }

    @PostMapping(value = "/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResponse query(@RequestBody(required = false) QueryRequest req) {
        publicRequestBudgetGuard.validateRag(req);
        return queryWithinBudget(req);
    }

    @PostMapping(value = "/probe", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResponse probe(@RequestBody(required = false) Map<String, Object> body) {
        QueryRequest req = new QueryRequest();
        Object q = body == null ? null : body.get("q");
        req.query = publicRequestBudgetGuard.requireRagProbeQuery(q);
        req.enableSelfAsk = true;
        req.whitelistOnly = false;
        publicRequestBudgetGuard.validateRag(req);
        return queryWithinBudget(req);
    }

    private QueryResponse queryWithinBudget(QueryRequest request) {
        TimeBudget budget = TimeBudgetContext.get();
        if (budget == null || endpointExecutor == null) {
            return orchestrator.query(request);
        }

        DeadlineProbe.enter("rag.endpoint");
        RequestTrace.markRequestStart();
        RequestTrace.emit("rag.endpoint", "enter", "");
        long remainingMs = Math.max(0L, budget.remainingMillis());
        if (remainingMs <= 0L) {
            DeadlineProbe.skip("rag.endpoint", "deadline_exhausted_at_entry");
            recordEndpointRejection("public_request_deadline_exhausted",
                    false, false, false, false);
            throw new EndpointRejection(HttpStatus.REQUEST_TIMEOUT,
                    "public_request_deadline_exhausted");
        }

        AtomicBoolean workerStarted = new AtomicBoolean(false);
        AtomicBoolean workerFinished = new AtomicBoolean(false);
        CompletableFuture<QueryResponse> future;
        try {
            RequestTrace.emit("rag.endpoint", "submit", "exec=ragEndpointExecutor");
            future = CompletableFuture.supplyAsync(
                    ContextPropagation.wrapSupplier(() -> {
                        workerStarted.set(true);
                        DeadlineProbe.enter("rag.worker");
                        RequestTrace.emit("rag.worker", "start", "");
                        try {
                            return orchestrator.query(request);
                        } finally {
                            workerFinished.set(true);
                            DeadlineProbe.finish("rag.worker");
                            RequestTrace.emit("rag.worker", "end",
                                    "cancelSeen=" + budget.cancelled());
                        }
                    }),
                    endpointExecutor);
        } catch (RejectedExecutionException rejected) {
            DeadlineProbe.skip("rag.endpoint", "executor_saturated");
            recordEndpointRejection("rag_endpoint_executor_saturated",
                    false, false, false, false);
            throw new EndpointRejection(HttpStatus.TOO_MANY_REQUESTS,
                    "rag_endpoint_executor_saturated");
        }

        remainingMs = Math.max(0L, budget.remainingMillis());
        if (remainingMs <= 0L) {
            budget.cancel();
            DeadlineProbe.cancel("rag.endpoint");
            RequestTrace.emit("rag.endpoint", "cancel_req", "phase=pre_wait");
            rejectAfterCancellation(future, workerStarted, workerFinished,
                    "public_request_deadline_exhausted");
        }

        try {
            QueryResponse response = future.get(remainingMs, TimeUnit.MILLISECONDS);
            RequestTrace.emit("rag.endpoint", "respond", "status=ok");
            return response;
        } catch (TimeoutException timeout) {
            // Mark the shared request budget cancelled so the still-running
            // worker's downstream budget checks stop starting new work
            // (cf. future.cancel(false) cannot interrupt a running task).
            budget.cancel();
            DeadlineProbe.cancel("rag.endpoint");
            RequestTrace.emit("rag.endpoint", "cancel_req", "phase=wait_timeout");
            rejectAfterCancellation(future, workerStarted, workerFinished,
                    "public_request_deadline_exhausted");
            throw new AssertionError("unreachable");
        } catch (InterruptedException interrupted) {
            budget.cancel();
            DeadlineProbe.cancel("rag.endpoint");
            RequestTrace.emit("rag.endpoint", "cancel_req", "phase=interrupted");
            boolean cancelAccepted = future.cancel(false);
            WorkerObservation observation = snapshotWorkerObservation(workerStarted, workerFinished);
            recordEndpointRejection("public_request_cancelled", true, cancelAccepted,
                    observation.started(), observation.finished());
            Thread.currentThread().interrupt();
            throw new EndpointRejection(HttpStatus.REQUEST_TIMEOUT, "public_request_cancelled");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            RequestTrace.emit("rag.endpoint", "respond",
                    "status=error;type=" + (cause == null ? "unknown" : cause.getClass().getSimpleName()));
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("RAG endpoint execution failed", cause);
        }
    }

    private static void rejectAfterCancellation(
            CompletableFuture<QueryResponse> future,
            AtomicBoolean workerStarted,
            AtomicBoolean workerFinished,
            String reasonCode) {
        boolean cancelAccepted = future.cancel(false);
        WorkerObservation observation = snapshotWorkerObservation(workerStarted, workerFinished);
        recordEndpointRejection(reasonCode, true, cancelAccepted,
                observation.started(), observation.finished());
        throw new EndpointRejection(HttpStatus.REQUEST_TIMEOUT, reasonCode);
    }

    private static WorkerObservation snapshotWorkerObservation(
            AtomicBoolean workerStarted,
            AtomicBoolean workerFinished) {
        boolean finished = workerFinished.get();
        boolean started = finished || workerStarted.get();
        return new WorkerObservation(started, finished);
    }

    private static void recordEndpointRejection(
            String reasonCode,
            boolean cancelRequested,
            boolean cancelAccepted,
            boolean workerStarted,
            boolean workerFinished) {
        TraceStore.put("rag.endpoint.reason", reasonCode);
        TraceStore.put("rag.endpoint.cancelRequested", cancelRequested);
        TraceStore.put("rag.endpoint.cancelAccepted", cancelAccepted);
        TraceStore.put("rag.endpoint.workerStarted", workerStarted);
        TraceStore.put("rag.endpoint.workerFinished", workerFinished);
        TraceStore.put("rag.endpoint.workerTermination",
                workerFinished ? "terminated" : workerStarted ? "unfinished" : "not_started");
        TraceStore.inc("rag.endpoint.rejected.count");
        // future.cancel()'s return value does not prove the queued task was
        // removed — the executor queue is opaque to us (CompletableFuture
        // wraps the task), so queue removal is honestly unobserved.
        RequestTrace.emit("rag.endpoint", "reject",
                "reason=" + reasonCode
                        + ";cancelRequested=" + cancelRequested
                        + ";cancelAccepted=" + cancelAccepted
                        + ";workerStarted=" + workerStarted
                        + ";workerFinished=" + workerFinished
                        + ";queue_removed=not_observed");
    }

    private record WorkerObservation(boolean started, boolean finished) {
    }

    private static final class EndpointRejection extends RuntimeException {
        private final HttpStatus status;
        private final String reasonCode;

        private EndpointRejection(HttpStatus status, String reasonCode) {
            super(reasonCode);
            this.status = status;
            this.reasonCode = reasonCode;
        }
    }

    @ExceptionHandler(EndpointRejection.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> ragEndpointRejected(
            EndpointRejection rejection) {
        return org.springframework.http.ResponseEntity.status(rejection.status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "status", rejection.status.value(),
                        "reasonCode", rejection.reasonCode));
    }

    @ExceptionHandler(PublicRequestBudgetGuard.Rejection.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> publicRequestBudgetRejected(
            PublicRequestBudgetGuard.Rejection rejection) {
        return org.springframework.http.ResponseEntity.status(rejection.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "status", rejection.status().value(),
                        "reasonCode", rejection.reasonCode()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> publicRequestBodyInvalid(
            HttpMessageNotReadableException ignored) {
        return org.springframework.http.ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "status", HttpStatus.BAD_REQUEST.value(),
                        "reasonCode", "rag_body_invalid"));
    }
}

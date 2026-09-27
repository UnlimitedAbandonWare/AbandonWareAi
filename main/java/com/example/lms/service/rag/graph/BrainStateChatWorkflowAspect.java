package com.example.lms.service.rag.graph;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 40)
public class BrainStateChatWorkflowAspect {

    private static final Logger log = LoggerFactory.getLogger(BrainStateChatWorkflowAspect.class);

    private final BrainStateProperties properties;
    private final GraphRagChunkingService chunkingService;
    private final Executor captureExecutor;

    public BrainStateChatWorkflowAspect(BrainStateProperties properties,
                                        GraphRagChunkingService chunkingService,
                                        @Qualifier("applicationTaskExecutor") Executor captureExecutor) {
        this.properties = properties;
        this.chunkingService = chunkingService;
        this.captureExecutor = captureExecutor;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.continueChat(com.example.lms.dto.ChatRequestDto,..))")
    public Object captureConversationTurn(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        if (result instanceof ChatResult chatResult && "cancelled".equals(chatResult.modelUsed())) {
            TraceStore.put("retrieval.kg.brainState.capture.skipped", "caller_cancelled");
        } else {
            TraceStore.put("retrieval.kg.brainState.capture.skipped", "awaiting_transcript_commit");
        }
        return result;
    }

    /** Called only after an exact-run fenced, approved assistant transcript commit. */
    public void captureFinalized(GeneralGraphScope scope, Long assistantMessageId) {
        captureFinalized(scope, null, assistantMessageId);
    }

    public void captureFinalized(GeneralGraphScope scope, Long userMessageId, Long assistantMessageId) {
        if ("caller_cancelled".equals(TraceStore.get("retrieval.kg.brainState.capture.skipped"))) return;
        if (!properties.isEnabled()
                || !properties.getIndexing().isEnabled()
                || !properties.getIndexing().isCaptureChatWorkflow()) {
            return;
        }
        if (!Boolean.TRUE.equals(TraceStore.get("finalAnswer.memorySaveAllowed"))) {
            TraceStore.put("retrieval.kg.brainState.capture.skipped", "memory_decision_denied_or_missing");
            return;
        }
        if (scope == null || !scope.memoryEnabled() || assistantMessageId == null || assistantMessageId <= 0) {
            TraceStore.put("retrieval.kg.brainState.capture.skipped", "source_authority_missing");
            return;
        }
        String sessionId = Long.toString(scope.sessionId());
        if (Thread.currentThread().isInterrupted()) {
            TraceStore.put("retrieval.kg.brainState.capture.skipped", "caller_cancelled");
            recordTerminal("cancelled", sessionId);
            return;
        }
        try {
            captureExecutor.execute(ContextPropagation.wrap(
                    () -> capture(scope, userMessageId, assistantMessageId)));
        } catch (RejectedExecutionException rejected) {
            recordTerminal("rejected", sessionId);
            log.debug("[AWX][brain-state][capture] rejected failureClass={} sessionHash={}",
                    safeFailureClass(rejected), BrainStateText.hash12(sessionId));
        }
    }

    void capture(GeneralGraphScope scope, long assistantMessageId) {
        capture(scope, null, assistantMessageId);
    }

    private void capture(GeneralGraphScope scope, Long userMessageId, long assistantMessageId) {
        String sessionId = Long.toString(scope.sessionId());
        try {
            GraphRagChunkingService.IngestReport report =
                    userMessageId == null ? chunkingService.ingestFinalizedTurn(scope, assistantMessageId)
                            : chunkingService.ingestFinalizedTurn(scope, userMessageId, assistantMessageId);
            recordTerminal(report == null ? "failed" : report.captureOutcome(), sessionId);
        } catch (CancellationException cancelled) {
            recordTerminal("cancelled", sessionId);
        } catch (Exception ex) {
            String failureClass = safeFailureClass(ex);
            recordTerminal("failed", sessionId);
            TraceStore.put("retrieval.kg.brainState.capture.failed", true);
            TraceStore.put("retrieval.kg.brainState.capture.failureClass", failureClass);
            TraceStore.put("retrieval.kg.brainState.capture.fallback", "skip_chat_capture");
            log.debug("[AWX][brain-state][capture] skipped failureClass={} sessionHash={}",
                    failureClass, BrainStateText.hash12(sessionId));
        }
    }

    private static void recordTerminal(String outcome, String sessionId) {
        String safeOutcome = switch (outcome == null ? "" : outcome) {
            case "succeeded", "queued", "partial_indexed", "skipped", "disabled",
                    "failed", "cancelled", "rejected" -> outcome;
            default -> "failed";
        };
        TraceStore.put("retrieval.kg.brainState.capture.outcome", safeOutcome);
        TraceStore.inc("retrieval.kg.brainState.capture.completionCount");
        TraceStore.put("retrieval.kg.brainState.capture.sessionHash", BrainStateText.hash12(sessionId));
    }

    private static String safeFailureClass(Throwable failure) {
        String name = failure == null ? "unknown" : failure.getClass().getSimpleName();
        return SafeRedactor.traceLabelOrFallback(name, "unknown");
    }

    private static ChatRequestDto firstRequest(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof ChatRequestDto request)) {
            return null;
        }
        return request;
    }
}

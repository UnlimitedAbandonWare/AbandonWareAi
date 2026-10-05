package com.example.lms.llm;

import ai.abandonware.nova.boot.exec.CancelShieldFuture;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class TimedChatModelCaller {

    private static final AtomicLong THREAD_IDS = new AtomicLong();
    private static final long WORKER_TERMINATION_GRACE_MS = 100L;
    private static final int DEFAULT_WORKERS = 4;
    private static final int DEFAULT_QUEUE_CAPACITY = 32;
    private static final Object EXECUTOR_LOCK = new Object();
    private static volatile ThreadPoolExecutor sharedExecutor;
    private static volatile int configuredWorkers = DEFAULT_WORKERS;
    private static volatile int configuredQueueCapacity = DEFAULT_QUEUE_CAPACITY;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                null,
                () -> shutdownSharedExecutor(1_000L),
                "awx-llm-hard-timeout-shutdown",
                0L,
                false));
    }

    private TimedChatModelCaller() {
    }

    /** Stop auxiliary verification on cancellation or terminal results without publishing judge output. */
    public static void rethrowIfCancelledOrTerminal(Throwable failure) {
        com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new CancellationException("verifier interrupted");
            }
            if (cursor instanceof CancellationException) {
                // Do not let a nested auxiliary terminal bypass cancellation upstream.
                throw new CancellationException("verifier cancelled");
            }
            Throwable next = cursor.getCause();
            if (next == cursor) break;
            cursor = next;
        }
        if (com.example.lms.llm.gateway.LlmResponseTerminalException.find(failure) != null) {
            // Auxiliary output must never become the public answer or its model receipt.
            throw new com.example.lms.llm.gateway.LlmResponseTerminalException(
                    "auxiliary_verification_terminated", com.example.lms.llm.gateway.LlmFailureClass.UNKNOWN,
                    "", null, "failed", null, null);
        }
    }

    public static AiMessage chat(
            ChatModel model,
            List<ChatMessage> messages,
            Duration timeout,
            String stage,
            String modelId) throws Exception {
        return chat(model, messages, timeout, stage, modelId, null);
    }

    public static AiMessage chat(
            ChatModel model,
            List<ChatMessage> messages,
            Duration timeout,
            String stage,
            String modelId,
            ChatUsageLedger.ModelAttempt usageAttempt) throws Exception {
        return chat(model, messages, timeout, stage, modelId, usageAttempt, null);
    }

    public static AiMessage chat(
            ChatModel model, List<ChatMessage> messages, Duration timeout,
            String stage, String modelId, ChatUsageLedger.ModelAttempt usageAttempt,
            java.util.concurrent.CompletableFuture<Boolean> modelWarmup) throws Exception {
        return callResponse(model, messages, timeout, stage, modelId, usageAttempt, modelWarmup, null).aiMessage();
    }

    public static ChatResponse chatResponse(ChatModel model, List<ChatMessage> messages, Duration timeout,
            String stage, String modelId, JudgeCallObservation observation) throws Exception {
        try {
            return callResponse(model, messages, timeout, stage, modelId, null, null, observation);
        } catch (Exception failure) {
            if (observation != null) observation.failed(failure);
            throw failure;
        }
    }

    private static ChatResponse callResponse(ChatModel model, List<ChatMessage> messages, Duration timeout,
            String stage, String modelId, ChatUsageLedger.ModelAttempt usageAttempt,
            java.util.concurrent.CompletableFuture<Boolean> modelWarmup, JudgeCallObservation observation) throws Exception {
        if (model == null) {
            if (observation != null) observation.skipped("judge_model_unavailable");
            if (usageAttempt != null) {
                usageAttempt.failedBeforeResponse();
            }
            throw new IllegalStateException("ChatModel is not configured");
        }
        if (observation == null) {
            TraceStore.putInternal("llm.call.responseModel", null);
            TraceStore.putInternal("llm.call.observation", null);
        }
        long timeoutMs = normalizeTimeoutMs(timeout);
        boolean acceptedRun = com.example.lms.service.chat.ChatRunExecutionContext.isAcceptedExecution();
        // Preload has no user prompt and has its own bounded lifetime. A caller's
        // deadline ends only its wait; never cancel the shared Ollama load.
        if (modelWarmup != null && !modelWarmup.isDone()) {
            TimeBudget loadBudget = acceptedRun ? null : TimeBudgetContext.get();
            long loadWaitMs = loadBudget == null ? timeoutMs : loadBudget.remainingMillis();
            TraceStore.put("llm.model.loading", true);
            long loadStarted = System.nanoTime();
            try {
                if (loadWaitMs <= 0) throw new TimeoutException("load budget exhausted");
                try (var wait = com.example.lms.service.chat.ChatRunExecutionContext.interruptibleCall("ollama_native")) {
                    if (acceptedRun) modelWarmup.get();
                    else modelWarmup.get(loadWaitMs, TimeUnit.MILLISECONDS);
                }
            } catch (TimeoutException exhausted) {
                if (usageAttempt != null) usageAttempt.timedOut();
                traceTerminal("model_loading_budget_exhausted", stage, modelId, null);
                throw new com.example.lms.llm.gateway.LlmGatewayException(
                        "Selected model is still loading", com.example.lms.llm.gateway.LlmFailureClass.TIMEOUT_SOFT,
                        "model_loading_budget_exhausted");
            } catch (ExecutionException unavailable) {
                // Preload is best effort; the ordinary guarded model call owns failure.
            } catch (InterruptedException interrupted) {
                if (usageAttempt != null) usageAttempt.cancelled();
                traceTerminal("caller_cancelled", stage, modelId, null);
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("caller_cancelled");
                cancelled.initCause(interrupted);
                throw cancelled;
            } catch (CancellationException cancelled) {
                if (usageAttempt != null) usageAttempt.cancelled();
                traceTerminal("caller_cancelled", stage, modelId, null);
                throw cancelled;
            } finally {
                TraceStore.put("llm.model.loadWaitMs", (System.nanoTime() - loadStarted) / 1_000_000L);
            }
        }
        TimeBudget requestBudget = acceptedRun ? null : TimeBudgetContext.get();
        long requestRemainingMs = requestBudget == null ? Long.MAX_VALUE : requestBudget.remainingMillis();
        if (requestRemainingMs <= 0L) {
            if (observation != null) observation.skipped("request_budget_exhausted");
            if (usageAttempt != null) usageAttempt.timedOut();
            traceTerminal("request_deadline_exhausted", stage, modelId, null);
            throw hardTimeout("LLM request deadline exhausted", null);
        }
        long effectiveWaitMs = requestBudget == null
                ? timeoutMs
                : Math.max(1L, Math.min(timeoutMs, requestRemainingMs));
        boolean requestDeadlineLimited = requestBudget != null && requestRemainingMs <= timeoutMs;
        ThreadPoolExecutor executor = executor();
        CountDownLatch taskExited = new CountDownLatch(1);
        Future<ChatResponse> delegate;
        try {
            delegate = executor.submit(ContextPropagation.wrapCallable(() -> {
                Thread.interrupted();
                try (var judgeBinding = observation == null ? null : observation.bind()) {
                    if (observation != null) observation.invocationStarted();
                    return model.chat(messages);
                } finally {
                    Thread.interrupted();
                    try {
                        var run = com.example.lms.service.chat.ChatRunExecutionContext.current();
                        if (run != null) {
                            org.slf4j.LoggerFactory.getLogger("com.example.lms.llm.ModelRuntimeHealthTracker.requestProof")
                                    .info("[LLM_WORKER_EXIT] phase=worker_exited scope=model_call runHash={} stage={} modelHash={} cancellationRequested={}",
                                            run.redactedRunIdentity(), SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                                            SafeRedactor.hashValue(modelId), run.isCancellationRequested());
                        }
                    } catch (RuntimeException ignored) {
                        // Diagnostics must not change the provider outcome or the exit fence.
                    } finally {
                        taskExited.countDown();
                    }
                }
            }));
        } catch (RejectedExecutionException rejected) {
            if (observation != null) observation.skipped("executor_saturated");
            if (usageAttempt != null) usageAttempt.failedBeforeResponse();
            traceTerminal("executor_saturated", stage, modelId, executor);
            throw new ExecutorSaturatedException("executor_saturated", rejected);
        }
        Future<ChatResponse> future = new CancelShieldFuture<>(delegate, "timed-chat-model-caller");
        try {
            ChatResponse response;
            try (var wait = acceptedRun
                    ? com.example.lms.service.chat.ChatRunExecutionContext.interruptibleCall("llm_wait") : null) {
                response = acceptedRun ? future.get() : future.get(effectiveWaitMs, TimeUnit.MILLISECONDS);
            }
            if (observation != null) observation.responseReceived(response);
            if (usageAttempt != null) {
                usageAttempt.responseReceived(response == null ? null : response.tokenUsage());
            }
            AiMessage ai = response == null ? null : response.aiMessage();
            String text = ai == null ? null : ai.text();
            if (text == null || text.isBlank()) {
                traceBlank(stage, modelId, text, response);
                traceTerminal("provider_hard_failure", stage, modelId, executor);
                throw new RuntimeException("LLM blank response");
            }
            if (isExpectedFailureResponse(text)) {
                traceTerminal("provider_hard_failure", stage, modelId, executor);
                throw new RuntimeException("LLM returned an expected-failure route marker");
            }
            if (usageAttempt != null) {
                usageAttempt.markSuccessful();
            }
            String responseModel = response.metadata() == null ? null : response.metadata().modelName();
            // Carry identity only after a successful answer; never reuse an
            // earlier attempt's model or publish arbitrary provider text.
            if (observation == null && responseModel != null && responseModel.matches("[A-Za-z0-9][A-Za-z0-9._:/+@-]{0,199}")) {
                TraceStore.putInternal("llm.call.responseModel", responseModel);
            }
            traceTerminal("success", stage, modelId, executor);
            return response;
        } catch (TimeoutException timeoutException) {
            if (usageAttempt != null) {
                usageAttempt.timedOut();
            }
            boolean requestedInterrupt = true;
            boolean effectiveInterrupt = false;
            boolean cancelAccepted = cancelAndPurge(executor, future, requestedInterrupt);
            boolean futureCancelledState = future.isCancelled();
            String workerTerminationEvidence = observeTaskExit(taskExited);
            String terminalReason = requestBudget != null
                    && (requestDeadlineLimited || requestBudget.expired())
                            ? "request_deadline_exhausted"
                            : "provider_timeout";
            traceTimeout(
                    timeoutMs,
                    effectiveWaitMs,
                    stage,
                    modelId,
                    requestedInterrupt,
                    effectiveInterrupt,
                    cancelAccepted,
                    futureCancelledState,
                    workerTerminationEvidence,
                    terminalReason,
                    executor);
            throw hardTimeout(
                    "request_deadline_exhausted".equals(terminalReason)
                            ? "LLM request deadline exhausted"
                            : "LLM call timed out",
                    timeoutException);
        } catch (CancellationException cancellationException) {
            // Cancellation can win between worker admission and wait-scope
            // registration. Stop must still reach the real admitted delegate.
            if (acceptedRun) {
                boolean cancelAccepted = cancelAndPurge(executor, delegate, true);
                traceCancellation("caller_cancelled", stage, modelId, cancelAccepted,
                        future.isCancelled(), observeTaskExit(taskExited), executor);
            }
            if (usageAttempt != null) {
                usageAttempt.cancelled();
            }
            traceTerminal("provider_cancelled", stage, modelId, executor);
            throw cancellationException;
        } catch (InterruptedException interruptedException) {
            if (usageAttempt != null) {
                usageAttempt.cancelled();
            }
            // Only an explicit accepted-run cancellation may interrupt its real
            // delegate. Wrapper cancellation alone is not worker-exit evidence.
            boolean cancelAccepted = cancelAndPurge(executor, acceptedRun ? delegate : future, true);
            String workerTerminationEvidence = observeTaskExit(taskExited);
            traceCancellation(
                    "caller_cancelled",
                    stage,
                    modelId,
                    cancelAccepted,
                    future.isCancelled(),
                    workerTerminationEvidence,
                    executor);
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("caller_cancelled");
            cancelled.initCause(interruptedException);
            throw cancelled;
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            var terminal = com.example.lms.llm.gateway.LlmResponseTerminalException.find(cause);
            if (terminal != null) {
                if (usageAttempt != null) usageAttempt.responseReceived(terminal.metadata().tokenUsage());
                traceTerminal(terminal.reasonCode(), stage, modelId, executor);
                throw terminal;
            }
            if (cause instanceof CancellationException providerCancelled) {
                if (usageAttempt != null) usageAttempt.cancelled();
                traceTerminal("provider_cancelled", stage, modelId, executor);
                throw providerCancelled;
            }
            if (cause instanceof InterruptedException providerInterrupted) {
                if (usageAttempt != null) usageAttempt.cancelled();
                traceTerminal("provider_cancelled", stage, modelId, executor);
                CancellationException cancelled = new CancellationException("provider_cancelled");
                cancelled.initCause(providerInterrupted);
                throw cancelled;
            }
            if (usageAttempt != null) usageAttempt.failedBeforeResponse();
            traceTerminal("provider_hard_failure", stage, modelId, executor);
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    public static boolean isExpectedFailureResponse(String text) {
        return text != null && text.contains("code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH");
    }

    public static boolean isHardTimeout(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof HardTimeoutException) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    private static final class HardTimeoutException extends TimeoutException {
        private HardTimeoutException(String message) {
            super(message);
        }
    }

    private static final class ExecutorSaturatedException extends RejectedExecutionException {
        private ExecutorSaturatedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static HardTimeoutException hardTimeout(String message, Throwable cause) {
        HardTimeoutException failure = new HardTimeoutException(message);
        if (cause != null) failure.initCause(cause);
        return failure;
    }

    private static long normalizeTimeoutMs(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return 1_000L;
        }
        return Math.max(1L, timeout.toMillis());
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(
                    null,
                    runnable,
                    "awx-llm-hard-timeout-" + THREAD_IDS.incrementAndGet(),
                    0L,
                    false);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String observeTaskExit(CountDownLatch taskExited) {
        try {
            return taskExited.await(WORKER_TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)
                    ? "observed"
                    : "not_observed";
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return "await_interrupted";
        }
    }

    private static void traceTimeout(
            long timeoutMs,
            long effectiveWaitMs,
            String stage,
            String modelId,
            boolean requestedInterrupt,
            boolean effectiveInterrupt,
            boolean cancelAccepted,
            boolean futureCancelledState,
            String workerTerminationEvidence,
            String terminalReason,
            ThreadPoolExecutor executor) {
        try {
            TraceStore.put("llm.call.timeout", true);
            TraceStore.put("llm.call.timeout.stage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
            TraceStore.put("llm.call.timeout.ms", timeoutMs);
            TraceStore.put("llm.call.timeout.effectiveMs", effectiveWaitMs);
            TraceStore.put("llm.call.timeout.modelHash", SafeRedactor.hashValue(modelId));
            TraceStore.put("llm.call.timeout.cancelRequestedInterrupt", requestedInterrupt);
            TraceStore.put("llm.call.timeout.cancelInterrupt", effectiveInterrupt);
            TraceStore.put("llm.call.timeout.cancelMayInterruptIfRunning", effectiveInterrupt);
            TraceStore.put("llm.call.timeout.cancelAccepted", cancelAccepted);
            TraceStore.put("llm.call.timeout.futureCancelledState", futureCancelledState);
            TraceStore.put("llm.call.timeout.workerTerminationEvidence", workerTerminationEvidence);
            traceTerminal(terminalReason, stage, modelId, executor);
        } catch (Exception ignored) {
            TraceStore.put("llm.call.timeout.suppressed", true);
        }
    }

    private static void traceCancellation(
            String terminalReason,
            String stage,
            String modelId,
            boolean cancelAccepted,
            boolean futureCancelledState,
            String workerTerminationEvidence,
            ThreadPoolExecutor executor) {
        TraceStore.put("llm.call.cancel.accepted", cancelAccepted);
        TraceStore.put("llm.call.cancel.futureCancelledState", futureCancelledState);
        TraceStore.put("llm.call.cancel.workerTerminationEvidence", workerTerminationEvidence);
        traceTerminal(terminalReason, stage, modelId, executor);
    }

    private static void traceTerminal(
            String reason,
            String stage,
            String modelId,
            ThreadPoolExecutor executor) {
        try {
            TraceStore.put("llm.call.terminalReason", reason);
            TraceStore.put("llm.call.terminalStage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
            TraceStore.put("llm.call.terminalModelHash", SafeRedactor.hashValue(modelId));
            if (executor != null) {
                TraceStore.put("llm.call.executor.poolSize", executor.getPoolSize());
                TraceStore.put("llm.call.executor.activeCount", executor.getActiveCount());
                TraceStore.put("llm.call.executor.queueSize", executor.getQueue().size());
                TraceStore.put("llm.call.executor.queueCapacity",
                        executor.getQueue().size() + executor.getQueue().remainingCapacity());
            }
        } catch (Exception ignored) {
            TraceStore.put("llm.call.terminalTrace.suppressed", true);
        }
    }

    private static ThreadPoolExecutor executor() {
        ThreadPoolExecutor current = sharedExecutor;
        if (current != null && !current.isShutdown() && !current.isTerminated()) return current;
        synchronized (EXECUTOR_LOCK) {
            current = sharedExecutor;
            if (current == null || current.isShutdown() || current.isTerminated()) {
                sharedExecutor = current = new CleanThreadPoolExecutor(
                        configuredWorkers,
                        configuredQueueCapacity,
                        daemonThreadFactory());
            }
            return current;
        }
    }

    private static boolean cancelAndPurge(
            ThreadPoolExecutor executor,
            Future<?> future,
            boolean requestedInterrupt) {
        boolean accepted = future.cancel(requestedInterrupt);
        executor.purge();
        return accepted;
    }

    private static final class CleanThreadPoolExecutor extends ThreadPoolExecutor {
        private CleanThreadPoolExecutor(int workers, int queueCapacity, ThreadFactory threadFactory) {
            super(
                    Math.max(1, workers),
                    Math.max(1, workers),
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                    threadFactory,
                    new AbortPolicy());
        }

        @Override
        protected void beforeExecute(Thread thread, Runnable task) {
            Thread.interrupted();
            super.beforeExecute(thread, task);
        }

        @Override
        protected void afterExecute(Runnable task, Throwable failure) {
            try {
                super.afterExecute(task, failure);
            } finally {
                Thread.interrupted();
            }
        }
    }

    static void resetSharedExecutorForTest(int workers, int queueCapacity) {
        shutdownSharedExecutor(1_000L);
        synchronized (EXECUTOR_LOCK) {
            configuredWorkers = Math.max(1, workers);
            configuredQueueCapacity = Math.max(1, queueCapacity);
            sharedExecutor = null;
        }
    }

    static boolean shutdownSharedExecutorForTest(long awaitMs) {
        boolean terminated = shutdownSharedExecutor(awaitMs);
        synchronized (EXECUTOR_LOCK) {
            configuredWorkers = DEFAULT_WORKERS;
            configuredQueueCapacity = DEFAULT_QUEUE_CAPACITY;
        }
        return terminated;
    }

    private static boolean shutdownSharedExecutor(long awaitMs) {
        ThreadPoolExecutor current;
        synchronized (EXECUTOR_LOCK) {
            current = sharedExecutor;
            sharedExecutor = null;
        }
        if (current == null) return true;
        current.shutdown();
        boolean terminated = awaitTermination(current, awaitMs);
        if (!terminated) {
            current.shutdownNow();
            terminated = awaitTermination(current, awaitMs);
        }
        return terminated;
    }

    private static boolean awaitTermination(ThreadPoolExecutor executor, long awaitMs) {
        try {
            return executor.awaitTermination(Math.max(1L, awaitMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean awaitSharedExecutorQuiescenceForTest(long awaitMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, awaitMs));
        while (System.nanoTime() < deadline) {
            ThreadPoolExecutor current = sharedExecutor;
            if (current == null || (current.getActiveCount() == 0 && current.getQueue().isEmpty())) return true;
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static Map<String, Integer> sharedExecutorMetricsForTest() {
        ThreadPoolExecutor current = sharedExecutor;
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("poolSize", current == null ? 0 : current.getPoolSize());
        out.put("active", current == null ? 0 : current.getActiveCount());
        out.put("queued", current == null ? 0 : current.getQueue().size());
        out.put("queueCapacity", current == null
                ? configuredQueueCapacity
                : current.getQueue().size() + current.getQueue().remainingCapacity());
        return Map.copyOf(out);
    }

    private static void traceBlank(String stage, String modelId, String text, ChatResponse response) {
        try {
            String finishReason = finishReason(response);
            int outputChars = text == null ? 0 : text.length();
            TraceStore.put("llm.call.blank", true);
            TraceStore.put("llm.output.blank", true);
            TraceStore.put("llm.call.blank.count", TraceStore.nextSequence("llm.call.blank"));
            TraceStore.put("llm.call.blank.stage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
            TraceStore.put("llm.call.blank.modelHash", SafeRedactor.hashValue(modelId));
            TraceStore.put("llm.call.blank.outputChars", outputChars);
            TraceStore.put("llm.output.contentLength", outputChars);
            TraceStore.put("llm.output.reason", "blank_response");
            TraceStore.put("llm.output.doneReason", finishReason);
            TraceStore.put("llm.upstream.pressure", 1.0d);
        } catch (Exception ignored) {
            TraceStore.put("llm.call.blank.suppressed", true);
        }
    }

    private static String finishReason(ChatResponse response) {
        Object reason = response == null ? null : response.finishReason();
        return SafeRedactor.traceLabelOrFallback(reason == null ? "unknown" : String.valueOf(reason), "unknown");
    }
}

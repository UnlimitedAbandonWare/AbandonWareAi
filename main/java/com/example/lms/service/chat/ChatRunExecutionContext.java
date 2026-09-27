package com.example.lms.service.chat;

import com.example.lms.dto.ChatStreamEvent;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.Objects;

/**
 * Opaque identity and cancellation/commit boundary for one chat execution.
 * The raw run identifier is intentionally not exposed outside the registry.
 */
public final class ChatRunExecutionContext {

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private final ChatRunRegistry registry;
    private final Long sessionId;
    private final String runId;
    private final Object commitLease;

    ChatRunExecutionContext(ChatRunRegistry registry, Long sessionId, String runId, Object commitLease) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.commitLease = commitLease;
    }

    public boolean isCancellationRequested() {
        return registry.isCancellationRequested(this);
    }

    /** Only short, nonblocking admission/observation callbacks may run under the run gate. */
    public boolean admitCall(Runnable onAdmitted) {
        return registry.admitCall(this, onAdmitted);
    }

    public void observeCancellation(String observationId, java.util.function.LongConsumer observer) {
        registry.observeCancellation(this, observationId, observer);
    }

    public String redactedRunIdentity() {
        return com.example.lms.trace.SafeRedactor.hashValue(runId);
    }

    public static void throwIfCancelled() {
        ChatRunExecutionContext run = current();
        if (Thread.currentThread().isInterrupted() || (run != null && !run.admitCall(() -> {}))) {
            throw new java.util.concurrent.CancellationException("exact chat run cancelled");
        }
    }

    /** Cap this wait against the original request deadline; zero never means unlimited I/O. */
    public static long capRequestWait(long configuredMillis) {
        throwIfCancelled();
        var budget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        long remaining = budget == null ? configuredMillis : budget.capWaitMillis(configuredMillis);
        if (remaining <= 0) {
            throw new com.example.lms.llm.gateway.LlmGatewayException("request budget exhausted",
                    com.example.lms.llm.gateway.LlmFailureClass.TIMEOUT_SOFT, "request_budget_exhausted");
        }
        return remaining;
    }

    /** Scope must be opened on the blocking caller, never on a shared I/O event loop. */
    public static BlockingCall interruptibleCall(String transport) {
        capRequestWait(Long.MAX_VALUE);
        return new BlockingCall(current(), Thread.currentThread(), transport);
    }

    public static final class BlockingCall implements AutoCloseable {
        private final ChatRunExecutionContext run;
        private final String id = java.util.UUID.randomUUID().toString();
        private final java.util.function.LongConsumer observer;

        private BlockingCall(ChatRunExecutionContext run, Thread caller, String transport) {
            this.run = run;
            String kind = java.util.Set.of("jdk_http", "ollama_native", "ollama_embedding").contains(transport)
                    ? transport : "http";
            observer = at -> {
                caller.interrupt();
                org.slf4j.LoggerFactory.getLogger("com.example.lms.llm.ModelRuntimeHealthTracker.requestProof")
                        .info("[LLM_TRANSPORT_CANCEL] runHash={} transport={} forwarding=caller_interrupt providerCompletion=not_observed",
                                run.redactedRunIdentity(), kind);
            };
            if (run != null && !run.registry.registerCancellationObserver(run, id, observer)) {
                throw new java.util.concurrent.CancellationException("exact run transport admission unavailable");
            }
            try { throwIfCancelled(); }
            catch (RuntimeException cancelled) { close(); throw cancelled; }
        }

        @Override public void close() {
            // Removal shares the cancellation gate; no callback can interrupt this thread after close returns.
            if (run != null) run.registry.removeCancellationObserver(run, id, observer);
        }
    }

    public boolean tryBeginCommit() {
        return registry.tryBeginCommit(this);
    }

    public boolean tryBeginTranscriptCommit() {
        return registry.tryBeginTranscriptCommit(this);
    }

    public boolean markGenerationSucceeded() {
        return registry.markGenerationSucceeded(this);
    }

    public boolean markPersisted() {
        return registry.markPersisted(this);
    }

    public boolean claimTerminalEvent(String reason) {
        return registry.claimTerminalEvent(this, reason);
    }

    public boolean recordFinalEmit(String emitResult, boolean clientDetached) {
        return registry.recordFinalEmit(this, emitResult, clientDetached);
    }

    public boolean recordTerminalWithoutFinal(String reason) {
        return registry.recordTerminalWithoutFinal(this, reason);
    }

    public boolean runTerminalSideEffect(Runnable action) {
        return registry.runTerminalSideEffect(this, action);
    }

    public boolean permitsEmission() {
        return registry.permitsEmission(this);
    }

    public boolean sameRun(ChatRunExecutionContext other) {
        return other != null
                && registry == other.registry
                && sessionId.equals(other.sessionId)
                && runId.equals(other.runId);
    }

    /** Opaque capability sent only to an authorized client for exact cancel/attach. */
    public String clientToken() {
        return runId;
    }

    public boolean belongsToSession(Long expectedSessionId) {
        return expectedSessionId != null && sessionId.equals(expectedSessionId);
    }

    public boolean registerCancellationHandle(Disposable handle) {
        return registry.registerCancellationHandle(this, handle);
    }

    public boolean awaitClientAcknowledgement(long timeoutMillis) {
        return registry.awaitClientAcknowledgement(this, timeoutMillis);
    }

    boolean registerProducerSink(Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink) {
        return registry.registerProducerSink(this, sink);
    }

    boolean unregisterProducerSink(Sinks.Many<ServerSentEvent<ChatStreamEvent>> expectedSink) {
        return registry.unregisterProducerSink(this, expectedSink);
    }

    boolean emitToProducer(ServerSentEvent<ChatStreamEvent> event) {
        return registry.emitToProducer(this, event);
    }

    Long sessionId() {
        return sessionId;
    }

    String runId() {
        return runId;
    }

    Object commitLease() {
        return commitLease;
    }

    boolean belongsTo(ChatRunRegistry expectedRegistry) {
        return registry == expectedRegistry;
    }

    public static ChatRunExecutionContext current() {
        Binding binding = CURRENT.get();
        return binding == null ? null : binding.context;
    }

    public static Scope bind(ChatRunExecutionContext context) {
        Objects.requireNonNull(context, "context");
        Binding binding = new Binding(context, CURRENT.get());
        CURRENT.set(binding);
        return new Scope(binding, Thread.currentThread());
    }

    private static final class Binding {
        private final ChatRunExecutionContext context;
        private final Binding previous;

        private Binding(ChatRunExecutionContext context, Binding previous) {
            this.context = context;
            this.previous = previous;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Binding binding;
        private final Thread ownerThread;
        private boolean closed;

        private Scope(Binding binding, Thread ownerThread) {
            this.binding = binding;
            this.ownerThread = ownerThread;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException("chat run scope closed on a different thread");
            }
            if (CURRENT.get() != binding) {
                throw new IllegalStateException("chat run scopes must close in LIFO order");
            }
            if (binding.previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(binding.previous);
            }
            closed = true;
        }
    }
}

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

    // Internal Focus capability on this exact run, never a public request field.
    private static final ThreadLocal<String> PROVIDER_TEXT_STAGE = new ThreadLocal<>();
    private volatile java.util.function.Consumer<String> foldTextConsumer;
    private volatile boolean foldStreamingAllowed;
    private final StringBuilder foldDeltas = new StringBuilder();
    private String foldPublished = "";
    private int foldDiagnosticStart = -1;
    private final long foldStartedNanos = System.nanoTime();
    private long foldFirstDeltaNanos, foldFirstUsefulNanos, foldFinalNanos;

    public void installFoldTextConsumer(java.util.function.Consumer<String> consumer) {
        if (foldTextConsumer != null) throw new IllegalStateException("focus_stream_already_installed");
        foldTextConsumer = Objects.requireNonNull(consumer);
    }
    public AutoCloseable permitFoldStreaming(boolean allowed) {
        boolean previous = foldStreamingAllowed;
        foldStreamingAllowed = allowed;
        return () -> foldStreamingAllowed = previous;
    }
    public static AutoCloseable bindProviderTextStage(String stage) {
        String previous = PROVIDER_TEXT_STAGE.get(); PROVIDER_TEXT_STAGE.set(stage);
        return () -> { if (previous == null) PROVIDER_TEXT_STAGE.remove(); else PROVIDER_TEXT_STAGE.set(previous); };
    }
    public static java.util.function.Consumer<String> providerTextConsumer() {
        var run = current();
        return run != null && run.foldTextConsumer != null && run.foldStreamingAllowed
                && "chat_draft".equals(PROVIDER_TEXT_STAGE.get()) ? run::acceptFoldDelta : null;
    }
    private synchronized void acceptFoldDelta(String delta) {
        if (!permitsEmission()) throw new java.util.concurrent.CancellationException("focus_stream_cancelled");
        if (!foldStreamingAllowed || delta == null) return;
        if (foldFirstDeltaNanos == 0) foldFirstDeltaNanos = System.nanoTime();
        if (foldDeltas.isEmpty()) delta = delta.stripLeading();
        foldDeltas.append(delta);
        var sanitizer = new com.example.lms.service.postprocess.OutputSanitizer();
        int cut = sanitizer.streamingDiagnosticStart(foldDeltas.toString());
        if (cut >= 0) foldDiagnosticStart = foldDiagnosticStart < 0 ? cut : Math.min(cut, foldDiagnosticStart);
        String safe = sanitizer.streamingPrefix(foldDeltas.toString(), foldDiagnosticStart);
        if (safe.length() <= foldPublished.length()) return;
        if (!safe.startsWith(foldPublished)) throw new IllegalStateException("focus_stream_final_mismatch");
        foldTextConsumer.accept(safe);
        foldPublished = safe;
        if (foldFirstUsefulNanos == 0) foldFirstUsefulNanos = System.nanoTime();
    }
    public synchronized boolean foldHasPublished() { return !foldPublished.isEmpty(); }
    public boolean foldStreamingRequested() { return foldTextConsumer != null; }
    public synchronized void requireFoldPrefix(String completed) {
        if (foldTextConsumer == null) return;
        if (!foldPublished.isEmpty() && (completed == null || !completed.stripLeading().startsWith(foldPublished)))
            throw new IllegalStateException("focus_stream_final_mismatch");
        if (foldFinalNanos == 0) foldFinalNanos = System.nanoTime();
    }
    public synchronized java.util.Map<String,Object> foldTimings() {
        var result = new java.util.LinkedHashMap<String,Object>();
        if (foldTextConsumer == null) return result;
        if (foldFirstDeltaNanos != 0) result.put("focus.stream.firstProviderDeltaMs", (foldFirstDeltaNanos-foldStartedNanos)/1_000_000L);
        if (foldFirstUsefulNanos != 0) result.put("focus.stream.firstUsefulPublishedMs", (foldFirstUsefulNanos-foldStartedNanos)/1_000_000L);
        if (foldFinalNanos != 0) result.put("focus.stream.finalValidatedMs", (foldFinalNanos-foldStartedNanos)/1_000_000L);
        result.put("focus.stream.publishedChars", foldPublished.length());
        return result;
    }

    ChatRunExecutionContext(ChatRunRegistry registry, Long sessionId, String runId, Object commitLease) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.commitLease = commitLease;
    }

    public java.util.Optional<com.example.lms.ensemble.PreparedContextPacket> cachedContextPreparation(String identity){
        return registry.cachedContextPreparation(this,identity);
    }
    public void rememberContextPreparation(com.example.lms.ensemble.PreparedContextPacket packet){
        registry.rememberContextPreparation(this,packet);
    }

    public boolean isCancellationRequested() {
        return registry.isCancellationRequested(this);
    }

    public com.example.lms.routing.RunRoutingSnapshot routingSnapshot() {
        return registry.routingSnapshot(this);
    }

    public java.util.Optional<com.example.lms.routing.RoutingInvocation> routingInvocation(com.example.lms.routing.RoutingProfile.Role role) {
        return registry.routingInvocation(this,role);
    }

    public boolean bindPersistedUserMessage(Long id) { return registry.bindPersistedUserMessage(this, id); }
    public Long persistedUserMessageId() { return registry.persistedUserMessageId(this); }
    public boolean rememberUnderstandingIntent(com.example.lms.service.understanding.UnderstandingCommitService.Intent intent) {
        return registry.rememberUnderstandingIntent(this, intent);
    }
    public java.util.Optional<com.example.lms.service.understanding.UnderstandingCommitService.Intent> understandingIntent() {
        return registry.understandingIntent(this);
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
        if (Thread.currentThread().isInterrupted() || (run != null && run.isCancellationRequested())) {
            throw new java.util.concurrent.CancellationException("exact chat run cancelled");
        }
    }

    /** Accepted runs retain finite transport waits without inheriting an ingress deadline. */
    public static long capRequestWait(long configuredMillis) {
        throwIfCancelled();
        if (isAcceptedExecution()) {
            if (configuredMillis <= 0) throw new IllegalArgumentException("positive transport wait required");
            return configuredMillis;
        }
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
        throwIfCancelled();
        if (!isAcceptedExecution()) {
            var budget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
            if (budget != null && budget.expired()) {
                throw new com.example.lms.llm.gateway.LlmGatewayException("request budget exhausted",
                        com.example.lms.llm.gateway.LlmFailureClass.TIMEOUT_SOFT, "request_budget_exhausted");
            }
        }
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

    /** The durable job worker owns task cancellation through its thread and commit fence. */
    public static Scope bindAcceptedTask() {
        Binding binding = new Binding(null, CURRENT.get());
        CURRENT.set(binding);
        return new Scope(binding, Thread.currentThread());
    }

    public static boolean isAcceptedExecution() {
        return CURRENT.get() != null;
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

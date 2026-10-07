package com.example.lms.service.chat;

import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.trace.SafeRedactor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.Disposable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;




/**
 * Registry for tracking in-flight chat runs.  Each chat session can have at most
 * one active run; subsequent subscribers can attach to the existing run and
 * replay buffered events.  Upon completion or cancellation the run is evicted
 * after a configurable TTL.  The underlying sink uses a replay buffer to
 * deliver all previously emitted events to late subscribers.
 */
@Component
public class ChatRunRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatRunRegistry.class);
    private static final int DEFAULT_INFLIGHT_IDLE_TIMEOUT_SECONDS = 1_800;
    private static final int MIN_INFLIGHT_IDLE_TIMEOUT_SECONDS = 30;
    private static final int MAX_INFLIGHT_IDLE_TIMEOUT_SECONDS = 86_400;
    private static final int MAX_STALE_SWEEP_INTERVAL_SECONDS = 30;
    private static final long DEFAULT_DELETION_CANCEL_WAIT_MILLIS = 5_000L;
    private static final long MAX_DELETION_CANCEL_WAIT_MILLIS = 5_000L;
    public static final String SESSION_DELETION_CANCEL_WAIT_TIMED_OUT =
            "session_deletion_cancel_wait_timed_out";
    public static final String SESSION_DELETION_CANCEL_WAIT_INTERRUPTED =
            "session_deletion_cancel_wait_interrupted";

    /** Status of a chat run. */
    public enum Status { RUNNING, COMMITTING, CANCELLING, DONE, CANCELLED }

    /** Result of atomically starting a run or joining the current in-flight run. */
    public record BeginResult(ChatRunExecutionContext context, boolean owner) { }

    /** Fixed, redacted failure when a session deletion fence cannot complete safely. */
    public static final class SessionDeletionFenceException extends RuntimeException {
        private final String reason;

        private SessionDeletionFenceException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        public static SessionDeletionFenceException waitTimedOut() {
            return new SessionDeletionFenceException(
                    SESSION_DELETION_CANCEL_WAIT_TIMED_OUT,
                    null);
        }

        private static SessionDeletionFenceException waitInterrupted(
                InterruptedException interrupted) {
            return new SessionDeletionFenceException(
                    SESSION_DELETION_CANCEL_WAIT_INTERRUPTED,
                    interrupted);
        }

        public String reason() {
            return reason;
        }
    }

    /** Redacted state projection for an already authorized exact-run request. */
    public record RunOutcomeView(
            String runIdentityHash,
            String generationOutcome,
            boolean generationSucceeded,
            boolean commitAttempted,
            boolean commitAccepted,
            boolean commitRejected,
            boolean persisted,
            int persistenceCount,
            String finalEmitResult,
            boolean finalDeliveryAccepted,
            String finalDeliveryFailureReason,
            int duplicateSuppressed,
            String terminalReason,
            int terminalEventCount,
            long elapsedMs) { }

    public record RunView(Status status, boolean current, RunOutcomeView outcome) {
        public boolean terminal() {
            return status == Status.DONE || status == Status.CANCELLED;
        }
    }

    /** Holder for per-session run state. */
    static final class Run {
        final Long sessionId;
        final String runId = UUID.randomUUID().toString();
        volatile long ownerLeaseDeadlineNanos = Long.MAX_VALUE;
        volatile boolean ownerLeaseConfirmed = true;
        volatile boolean clusterFinalized;
        String verifiedOwnerHash;
        String priorEvidenceOwnerHash;
        List<dev.langchain4j.rag.content.Content> finalWebEvidence = List.of();
        List<dev.langchain4j.rag.content.Content> priorWebEvidence = List.of();
        long evidenceExpiresAtMillis;
        final Object gate = new Object();
        final ReentrantLock terminalWriteLock = new ReentrantLock();
        volatile boolean terminalWriteInProgress;
        boolean cancellationCallbacksComplete;
        List<ServerSentEvent<ChatStreamEvent>> cancellationTerminalEvidence = List.of();
        final Object commitLease = new Object();
        final Object routingState = new Object();
        volatile com.example.lms.routing.RunRoutingSnapshot routingSnapshot;
        volatile com.example.lms.api.SettingsPlanProjection.View routingPlan;
        final Map<com.example.lms.routing.RoutingProfile.Role,com.example.lms.routing.RoutingInvocation> routingInvocations = new java.util.EnumMap<>(com.example.lms.routing.RoutingProfile.Role.class);
        com.example.lms.ensemble.PreparedContextPacket contextPreparation;
        Long persistedUserMessageId;
        com.example.lms.service.understanding.UnderstandingCommitService.Intent understandingIntent;
        final Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink;
        volatile Status status = Status.RUNNING;
        long lastProgressMillis;
        boolean idleDiagnosticRecorded;
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> producerSink;
        Disposable cancellationHandle;
        boolean terminalEvictionScheduled;
        volatile boolean deletionFence;
        boolean transcriptCommitClaimed;
        boolean clientAcknowledged;
        int interactiveSubscribers;
        final long outcomeStartedNanos = System.nanoTime();
        String generationOutcome = "pending";
        boolean generationSucceeded;
        boolean commitAttempted;
        boolean commitAccepted;
        boolean commitRejected;
        boolean persisted;
        int persistenceCount;
        boolean terminalEventClaimed;
        String finalEmitResult = "not_attempted";
        boolean finalDeliveryAccepted;
        String finalDeliveryFailureReason = "not_observed";
        int duplicateSuppressed;
        String terminalReason = "pending";
        int terminalEventCount;
        final CountDownLatch clientAcknowledgement = new CountDownLatch(1);
        final CountDownLatch cancellationComplete = new CountDownLatch(1);
        long cancellationAcceptedAtEpochMs;
        final Map<String, java.util.function.LongConsumer> cancellationObservers = new java.util.LinkedHashMap<>();

        Run(Long sessionId, Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink, long startedMillis) {
            this.sessionId = sessionId;
            this.sink = sink;
            this.lastProgressMillis = startedMillis;
        }
    }

    /** Current-run pointer per session. Terminal runs remain token-addressable until TTL. */
    private final Map<Long, Run> runs = new ConcurrentHashMap<>();
    private final Map<String, Run> runsByToken = new ConcurrentHashMap<>();

    private final ScheduledExecutorService evictor;
    private final LongSupplier clock;
    private final boolean ownsEvictor;
    private final AtomicBoolean staleSweepStarted = new AtomicBoolean();
    private volatile ScheduledFuture<?> staleSweep;
    private volatile ScheduledFuture<?> ownerSweep;
    private ChatRunCluster cluster;
    private com.example.lms.routing.RoutingProfileResolver routingProfileResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRoutingProfileResolver(com.example.lms.routing.RoutingProfileResolver resolver) {
        this.routingProfileResolver = resolver;
    }

    private com.example.lms.routing.RunRoutingSnapshot captureRouting(Run run) {
        synchronized (run.routingState) {
            if (run.routingSnapshot == null) {
                try {
                    run.routingSnapshot = routingProfileResolver == null
                            ? com.example.lms.routing.RunRoutingSnapshot.disabled() : routingProfileResolver.capture();
                } catch (com.example.lms.routing.RoutingProfileResolver.Unavailable unavailable) {
                    run.routingSnapshot = com.example.lms.routing.RunRoutingSnapshot.unavailable();
                }
            }
            return run.routingSnapshot;
        }
    }

    com.example.lms.routing.RunRoutingSnapshot routingSnapshot(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        if (run == null) throw new com.example.lms.routing.RoutingProfileResolver.Unavailable();
        return captureRouting(run);
    }

    Optional<com.example.lms.routing.RoutingInvocation> routingInvocation(ChatRunExecutionContext context,com.example.lms.routing.RoutingProfile.Role role) {
        Run run=exactRun(context);
        if(run==null)throw new com.example.lms.routing.RoutingProfileResolver.Unavailable();
        synchronized(run.routingState){
            return captureRouting(run).binding(role).map(binding->run.routingInvocations.computeIfAbsent(role,
                    ignored->new com.example.lms.routing.RoutingInvocation(binding)));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCluster(ChatRunCluster cluster) { this.cluster = cluster; }

    /** Local monotonic deadline is conservative relative to the DB lease. */
    private boolean leaseValid(Run run) {
        return cluster == null || (run.ownerLeaseConfirmed && System.nanoTime() < run.ownerLeaseDeadlineNanos);
    }

    void renewOwnerLeases() {
        if (cluster == null) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ChatRunOwnerDirectory.LEASE_MILLIS);
        var active = runsByToken.values().stream()
                .filter(r -> isInFlight(r.status) || r.terminalWriteInProgress).toList();
        java.util.Set<String> renewed;
        try {
            renewed = cluster.directory().renew(active.stream().map(r -> r.runId).collect(java.util.stream.Collectors.toSet()));
        } catch (RuntimeException unavailable) {
            active.forEach(run -> run.ownerLeaseConfirmed = false);
            log.warn("[AWX][chat-run] owner renewal unavailable; ownership=unknown action=retain_run_and_fence_authority");
            return;
        }
        for (Run run : active) {
            if (renewed.contains(run.runId)) {
                run.ownerLeaseDeadlineNanos = deadline;
                run.ownerLeaseConfirmed = true;
            } else {
                run.ownerLeaseConfirmed = false;
                // Missing renewal or elapsed lease is not proof that the worker died.
                // Retain the run; leaseValid still fences new calls and durable effects.
                try {
                    if (cluster.directory().currentToken(run.sessionId)
                            .filter(token -> !token.equals(run.runId)).isPresent()) {
                        cancelRun(run, () -> { }, false, true);
                    }
                } catch (RuntimeException unavailable) {
                    log.warn("[AWX][chat-run] owner lookup unavailable; ownership=unknown action=retain_run_and_fence_authority");
                }
            }
        }
        for (Run run : runsByToken.values()) {
            if (isTerminal(run.status) && !run.clusterFinalized) finishClusterOwner(run);
        }
        try { cluster.directory().prune(); } catch (RuntimeException unavailable) { /* Retry on next bounded sweep. */ }
    }

    private void finishClusterOwner(Run run) {
        if (cluster == null || run.clusterFinalized) return;
        try { run.clusterFinalized = cluster.directory().finish(run.sessionId, run.runId, ttlSeconds); }
        catch (RuntimeException unavailable) {
            log.warn("[AWX][chat-run] owner release unavailable; no automatic takeover");
        }
    }

    /** Capacity of the replay buffer when replaying past events on reattach. */
    @Value("${chat.resume.replay-capacity:512}")
    int replayCapacity;

    /** Time-to-live for completed or cancelled runs (in seconds). */
    @Value("${chat.resume.ttl-seconds:300}")
    int ttlSeconds;

    /** Compatibility key: diagnostic idle age, never a nonterminal run deadline. */
    @Value("${chat.resume.inflight-idle-timeout-seconds:1800}")
    int inflightIdleTimeoutSeconds = DEFAULT_INFLIGHT_IDLE_TIMEOUT_SECONDS;

    /** Maximum wait for an already-running exact cancellation before delete retries. */
    @Value("${chat.resume.deletion-cancel-wait-millis:5000}")
    long deletionCancelWaitMillis = DEFAULT_DELETION_CANCEL_WAIT_MILLIS;

    public ChatRunRegistry() {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-run-evictor");
            t.setDaemon(true);
            return t;
        }), System::currentTimeMillis, true);
    }

    ChatRunRegistry(ScheduledExecutorService evictor) {
        this(evictor, System::currentTimeMillis, false);
    }

    ChatRunRegistry(ScheduledExecutorService evictor, LongSupplier clock, boolean ownsEvictor) {
        this.evictor = Objects.requireNonNull(evictor, "evictor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownsEvictor = ownsEvictor;
    }

    @PostConstruct
    void startStaleSweep() {
        if (!staleSweepStarted.compareAndSet(false, true)) {
            return;
        }
        if (cluster != null) ownerSweep = evictor.scheduleWithFixedDelay(this::renewOwnerLeases, 2, 2, TimeUnit.SECONDS);
        long intervalSeconds = staleSweepIntervalSeconds();
        staleSweep = evictor.scheduleWithFixedDelay(
                this::runStaleSweepSafely,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        if (ownerSweep != null) ownerSweep.cancel(true);
        ScheduledFuture<?> sweep = staleSweep;
        if (sweep != null) {
            sweep.cancel(true);
        }
        if (ownsEvictor) {
            evictor.shutdownNow();
        }
    }

    private void runStaleSweepSafely() {
        try {
            runStaleSweepNow();
        } catch (Throwable failure) {
            log.warn("[AWX][chat-run] stale sweep failed stage=nonterminal-expiry type={}",
                    failure.getClass().getSimpleName());
        }
    }

    private int normalizedInflightIdleTimeoutSeconds() {
        int configured = inflightIdleTimeoutSeconds > 0
                ? inflightIdleTimeoutSeconds
                : DEFAULT_INFLIGHT_IDLE_TIMEOUT_SECONDS;
        return Math.max(
                MIN_INFLIGHT_IDLE_TIMEOUT_SECONDS,
                Math.min(MAX_INFLIGHT_IDLE_TIMEOUT_SECONDS, configured));
    }

    private long staleSweepIntervalSeconds() {
        return Math.max(
                1L,
                Math.min(
                        MAX_STALE_SWEEP_INTERVAL_SECONDS,
                        normalizedInflightIdleTimeoutSeconds() / 2L));
    }

    void runStaleSweepNow() {
        long nowMillis = clock.getAsLong();
        long idleTimeoutMillis = TimeUnit.SECONDS.toMillis(normalizedInflightIdleTimeoutSeconds());
        for (Run candidate : runs.values()) {
            timeoutIfStale(candidate, nowMillis, idleTimeoutMillis);
        }
    }

    private void timeoutIfStale(Run expectedRun, long nowMillis, long idleTimeoutMillis) {
        if (expectedRun == null) return;
        synchronized (expectedRun.gate) {
            if (runs.get(expectedRun.sessionId) != expectedRun
                    || runsByToken.get(expectedRun.runId) != expectedRun
                    || isTerminal(expectedRun.status)
                    || expectedRun.idleDiagnosticRecorded
                    || idleAgeMillis(nowMillis, expectedRun.lastProgressMillis) < idleTimeoutMillis) return;
            expectedRun.idleDiagnosticRecorded = true;
            log.info("[AWX] timeoutSource=ChatRunRegistry.idleDiagnostic phase=run_liveness runHash={} action=retain_run workerExit=not_observed",
                    SafeRedactor.hashValue(expectedRun.runId));
        }
    }

    /**
     * Atomically start a run or return the exact current run. Only the caller that
     * installs a new run is its execution owner.
     */
    public BeginResult beginOrJoin(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is required");
        }
        AtomicBoolean owner = new AtomicBoolean(false);
        Run run = runs.compute(sessionId, (id, existing) -> {
            if (existing == null || (isTerminal(existing.status) && !existing.deletionFence)) {
                Run created = new Run(id, Sinks.many().replay().limit(replayCapacity), clock.getAsLong());
                if (existing != null) {
                    synchronized (existing.gate) {
                        if (existing.status == Status.DONE && !existing.deletionFence
                                && existing.generationSucceeded && existing.persisted
                                && existing.finalDeliveryAccepted && "delivered".equals(existing.terminalReason)
                                && "ok".equals(existing.finalEmitResult) && existing.terminalEventCount == 1
                                && clock.getAsLong() < existing.evidenceExpiresAtMillis) {
                            created.priorWebEvidence = existing.finalWebEvidence;
                            created.priorEvidenceOwnerHash = existing.verifiedOwnerHash;
                        }
                    }
                }
                if (cluster != null) {
                    created.ownerLeaseDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ChatRunOwnerDirectory.LEASE_MILLIS);
                    cluster.directory().claim(id, created.runId);
                }
                owner.set(true);
                runsByToken.put(created.runId, created);
                return created;
            }
            return existing;
        });
        captureRouting(run); // Outside the commit/cancellation gate and map mutation.
        return new BeginResult(contextFor(run, owner.get()), owner.get());
    }

    private static boolean validOwnerHash(String hash) {
        return hash != null && hash.matches("[a-f0-9]{64}");
    }

    /** Private process-local carry; only the new exact execution owner can read it. */
    public List<dev.langchain4j.rag.content.Content> priorWebEvidence(ChatRunExecutionContext context, String ownerHash) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) return List.of();
        synchronized (run.gate) {
            if (runs.get(run.sessionId) != run || !isInFlight(run.status) || run.deletionFence || !leaseValid(run)
                    || !validOwnerHash(ownerHash)) return List.of();
            if (run.verifiedOwnerHash == null) run.verifiedOwnerHash = ownerHash;
            return ownerHash.equals(run.verifiedOwnerHash) && ownerHash.equals(run.priorEvidenceOwnerHash)
                    ? run.priorWebEvidence : List.of();
        }
    }

    /** Stage final citable passages; they cannot be reused before normal final delivery. */
    public boolean stageWebEvidence(ChatRunExecutionContext context, String ownerHash,
            List<dev.langchain4j.rag.content.Content> documents) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) return false;
        var bounded = new java.util.ArrayList<dev.langchain4j.rag.content.Content>();
        int chars = 0;
        for (var doc : documents == null ? List.<dev.langchain4j.rag.content.Content>of() : documents) {
            if (doc == null || doc.textSegment() == null || bounded.size() == 8) continue;
            String body = doc.textSegment().text();
            String url = doc.textSegment().metadata().getString("url");
            if (body == null || body.isBlank() || body.length() > 8000 || chars + body.length() > 48000
                    || url == null || !url.matches("(?i)^https?://[^\\s]+$") || url.length() > 1000) continue;
            bounded.add(dev.langchain4j.rag.content.Content.from(dev.langchain4j.data.segment.TextSegment.from(body,
                    dev.langchain4j.data.document.Metadata.from(Map.of("url",url,"kind","WEB")))));
            chars += body.length();
        }
        synchronized (run.gate) {
            if (runs.get(run.sessionId) != run || !isInFlight(run.status) || run.deletionFence || !leaseValid(run)
                    || !validOwnerHash(ownerHash) || !ownerHash.equals(run.verifiedOwnerHash)) return false;
            run.finalWebEvidence = List.copyOf(bounded);
            return true;
        }
    }

    /** Attach to one current in-flight run using one map snapshot. */
    public Optional<Flux<ServerSentEvent<ChatStreamEvent>>> attachRunning(Long sessionId) {
        Run run = sessionId == null ? null : runs.get(sessionId);
        if (run == null || !isInFlight(run.status)) {
            return Optional.empty();
        }
        return Optional.of(run.sink.asFlux());
    }

    /** Attach to the exact token-addressed run, including a retained terminal replay. */
    public Optional<Flux<ServerSentEvent<ChatStreamEvent>>> attachExact(Long sessionId, String runToken) {
        Run run = exactRun(sessionId, runToken);
        return run == null ? Optional.empty() : Optional.of(run.sink.asFlux());
    }

    /** Only HTTP consumers use this wrapper; internal replay bridges remain untracked. */
    public Optional<Flux<ServerSentEvent<ChatStreamEvent>>> attachInteractiveExact(Long sessionId, String runToken) {
        Run run = exactRun(sessionId, runToken);
        if (run == null && cluster != null) return cluster.attach(sessionId, runToken);
        return run == null ? Optional.empty() : Optional.of(Flux.defer(() -> {
            InteractiveClient client = interactiveClient();
            client.bind(contextFor(run, false));
            return interactiveSource(client, run.sink.asFlux());
        }));
    }

    public InteractiveClient interactiveClient() { return new InteractiveClient(); }

    /** Comment writes allow the servlet transport to observe a closed idle socket. */
    public Flux<ServerSentEvent<ChatStreamEvent>> interactiveSource(
            InteractiveClient client, Flux<ServerSentEvent<ChatStreamEvent>> source) {
        return source.publish(shared -> Flux.merge(shared,
                Flux.interval(java.time.Duration.ofMillis(250))
                        .map(tick -> ServerSentEvent.<ChatStreamEvent>builder().comment("keepalive").build())
                        .takeUntilOther(shared.ignoreElements())))
                .doFinally(signal -> client.close(signal == reactor.core.publisher.SignalType.CANCEL
                        || signal == reactor.core.publisher.SignalType.ON_ERROR));
    }

    /** Handles disconnect both before and after asynchronous session creation. */
    public final class InteractiveClient {
        private Run attached;
        private boolean closed;
        private boolean disconnected;

        public void bind(ChatRunExecutionContext context) {
            Run next = exactRun(context), previous;
            boolean releaseImmediately, cancel;
            synchronized (this) {
                if (next == null || next == attached) return;
                previous = attached;
                attached = next;
                synchronized (next.gate) { next.interactiveSubscribers++; }
                releaseImmediately = closed;
                cancel = disconnected;
            }
            if (previous != null && !releaseImmediately) release(previous, false);
            if (releaseImmediately) release(next, cancel);
        }

        public void disconnect() { close(true); }

        private void close(boolean cancel) {
            Run run;
            synchronized (this) {
                if (closed) return;
                closed = true;
                disconnected = cancel;
                run = attached;
            }
            if (run != null) release(run, cancel);
        }

        private void release(Run run, boolean cancel) {
            synchronized (run.gate) { run.interactiveSubscribers--; }
            // Subscriber lifetime belongs to the transport. Explicit Stop owns cancellation.
        }
    }

    /** Attach to the exact run represented by the opaque context. */
    public Flux<ServerSentEvent<ChatStreamEvent>> attach(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        return run == null ? Flux.empty() : run.sink.asFlux();
    }

    /** Emit into the replay buffer only while this exact run remains in flight. */
    public boolean emit(ChatRunExecutionContext context,
                        ServerSentEvent<ChatStreamEvent> event) {
        if (event == null) {
            return false;
        }
        Run run = exactRun(context);
        if (run == null) {
            return false;
        }
        synchronized (run.gate) {
            if (!isInFlight(run.status) || !leaseValid(run)) {
                return false;
            }
            boolean emitted = run.sink.tryEmitNext(event).isSuccess();
            if (emitted) {
                if(event.data()!=null && event.data().pipelineSnapshot()!=null)
                    run.routingPlan=com.example.lms.api.SettingsPlanProjection.fromPipeline(event.data().pipelineSnapshot());
                touchProgress(run);
            }
            return emitted;
        }
    }

    /** Exact cancellation used by the HTTP boundary. Stale tokens are rejected. */
    public boolean cancelExact(Long sessionId, String runToken) {
        return cancelExact(sessionId, runToken, () -> { });
    }

    /**
     * Exact cancellation with a terminal action. The run remains non-replaceable
     * in CANCELLING until the action finishes, preventing a delayed R1 terminal
     * record from being appended after R2 starts.
     */
    public boolean cancelExact(Long sessionId, String runToken, Runnable beforeTerminal) {
        if (exactRun(sessionId, runToken) == null && cluster != null) return cluster.control("cancel", sessionId, runToken);
        return cancelRun(exactRun(sessionId, runToken), beforeTerminal, false, false);
    }

    /**
     * Fence every execution identity for a session before its durable history is
     * deleted. Unlike ordinary exact-token Stop, this also cancels a run that has
     * entered COMMITTING and keeps the terminal session id non-replaceable until
     * the existing terminal TTL expires.
     */
    public void cancelSessionForDeletion(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is required");
        }
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(boundedDeletionCancelWaitMillis());
        if (cluster != null) cluster.fenceDeletion(sessionId);
        AtomicReference<Run> selected = new AtomicReference<>();
        runs.compute(sessionId, (id, existing) -> {
            Run run = existing;
            if (run == null || isTerminal(run.status)) {
                run = new Run(
                        id,
                        Sinks.many().replay().limit(Math.max(1, replayCapacity)),
                        clock.getAsLong());
                runsByToken.put(run.runId, run);
            }
            synchronized (run.gate) {
                run.deletionFence = true;
            }
            selected.set(run);
            return run;
        });

        Run run = selected.get();
        cancelRun(run, () -> { }, false, true);

        boolean cancellationInProgress;
        synchronized (run.gate) {
            cancellationInProgress = run.status == Status.CANCELLING;
            if (isTerminal(run.status)) {
                run.terminalReason = "session_deleted";
            }
        }
        if (cancellationInProgress) {
            try {
                long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
                boolean completed = run.cancellationComplete.await(remaining, TimeUnit.NANOSECONDS);
                if (!completed) {
                    throw SessionDeletionFenceException.waitTimedOut();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw SessionDeletionFenceException.waitInterrupted(interrupted);
            }
        }
        scheduleTerminalEvictionOnce(run);
    }

    private long boundedDeletionCancelWaitMillis() {
        return Math.max(1L, Math.min(MAX_DELETION_CANCEL_WAIT_MILLIS, deletionCancelWaitMillis));
    }

    /** Record that the exact client received its run capability. */
    public boolean acknowledgeExact(Long sessionId, String runToken) {
        Run run = exactRun(sessionId, runToken);
        if (run == null && cluster != null) return cluster.control("ready", sessionId, runToken);
        if (run == null) {
            return false;
        }
        synchronized (run.gate) {
            if (!isInFlight(run.status)) {
                return false;
            }
            run.clientAcknowledged = true;
            run.clientAcknowledgement.countDown();
            touchProgress(run);
            return true;
        }
    }

    /** Record browser-visible final delivery for one retained exact run. */
    public boolean acknowledgeFinalDeliveryExact(Long sessionId, String runToken) {
        Run run = exactRun(sessionId, runToken);
        if (run == null && cluster != null) return cluster.control("final", sessionId, runToken);
        if (run == null) {
            return false;
        }
        synchronized (run.gate) {
            if (!run.persisted || !"ok".equals(run.finalEmitResult) || run.terminalEventCount != 1) {
                return false;
            }
            run.finalDeliveryAccepted = true;
            run.finalDeliveryFailureReason = "none";
            run.terminalReason = "delivered";
            if (isInFlight(run.status)) {
                touchProgress(run);
            }
            return true;
        }
    }

    /** Record delivery restored from the persisted transcript after transport loss. */
    public boolean acknowledgeRecoveredDeliveryExact(Long sessionId, String runToken) {
        Run run = exactRun(sessionId, runToken);
        if (run == null && cluster != null) return cluster.control("recovery", sessionId, runToken);
        if (run == null) return false;
        synchronized (run.gate) {
            if (!run.persisted || !run.terminalEventClaimed) return false;
            run.finalDeliveryAccepted = true;
            run.finalDeliveryFailureReason = "none";
            run.terminalReason = "recovered";
            if (isInFlight(run.status)) {
                touchProgress(run);
            }
            return true;
        }
    }

    /** Cancel an owner run only when its capability was never acknowledged. */
    public boolean cancelIfUnacknowledged(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        return isOwner(run, context) && cancelRun(run, () -> { }, true, false);
    }

    /**
     * Cancel an unacknowledged owner run and atomically append its already typed
     * terminal evidence before the exact replay sink is completed.
     */
    public boolean cancelIfUnacknowledged(
            ChatRunExecutionContext context,
            List<ServerSentEvent<ChatStreamEvent>> terminalEvidence) {
        List<ServerSentEvent<ChatStreamEvent>> evidence =
                List.copyOf(Objects.requireNonNull(terminalEvidence, "terminalEvidence"));
        Run run = exactRun(context);
        return isOwner(run, context)
                && cancelRun(run, () -> { }, true, false, evidence);
    }

    boolean awaitClientAcknowledgement(ChatRunExecutionContext context, long timeoutMillis) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) {
            return false;
        }
        try {
            run.clientAcknowledgement.await(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        synchronized (run.gate) {
            return run.clientAcknowledged && isInFlight(run.status);
        }
    }

    /** Complete one exact run. A stale owner can never complete its replacement. */
    public boolean markDone(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) {
            return false;
        }
        synchronized (run.gate) {
            if (!isInFlight(run.status) || run.terminalWriteInProgress) {
                return false;
            }
            run.status = Status.DONE;
            run.clientAcknowledgement.countDown();
            run.sink.tryEmitComplete();
            run.cancellationHandle = null;
        }
        scheduleTerminalEvictionOnce(run);
        return true;
    }

    boolean tryBeginCommit(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) {
            return false;
        }
        synchronized (run.gate) {
            if (run.status != Status.RUNNING || !leaseValid(run)) {
                return false;
            }
            run.status = Status.COMMITTING;
            touchProgress(run);
            return true;
        }
    }

    /**
     * Claim the transcript persistence stage once. It may be the first durable
     * stage when workflow memory persistence was skipped, or follow that stage.
     */
    boolean tryBeginTranscriptCommit(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) {
            return false;
        }
        synchronized (run.gate) {
            run.commitAttempted = true;
            if (!isInFlight(run.status) || !leaseValid(run) || run.transcriptCommitClaimed) {
                run.commitRejected = true;
                return false;
            }
            run.status = Status.COMMITTING;
            run.transcriptCommitClaimed = true;
            run.commitAccepted = true;
            touchProgress(run);
            return true;
        }
    }

    boolean markGenerationSucceeded(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) return false;
        synchronized (run.gate) {
            if (!isInFlight(run.status)) return false;
            run.generationSucceeded = true;
            run.generationOutcome = "success";
            touchProgress(run);
            return true;
        }
    }

    boolean markPersisted(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) return false;
        synchronized (run.gate) {
            if (!run.transcriptCommitClaimed || !isInFlight(run.status)) {
                return false;
            }
            if (run.persisted) {
                run.duplicateSuppressed++;
                return false;
            }
            run.persisted = true;
            run.persistenceCount = 1;
            touchProgress(run);
            return true;
        }
    }

    boolean claimTerminalEvent(ChatRunExecutionContext context, String reason) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) return false;
        synchronized (run.gate) {
            if (!isInFlight(run.status) || run.terminalEventClaimed) {
                run.duplicateSuppressed++;
                return false;
            }
            run.terminalEventClaimed = true;
            run.terminalReason = boundedReason(reason, "terminal_pending");
            touchProgress(run);
            return true;
        }
    }

    boolean recordFinalEmit(
            ChatRunExecutionContext context,
            String emitResult,
            boolean clientDetached) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) return false;
        synchronized (run.gate) {
            if (!isInFlight(run.status)) return false;
            String normalized = boundedReason(emitResult, "unknown").toLowerCase(java.util.Locale.ROOT);
            run.finalEmitResult = normalized;
            if ("ok".equals(normalized)) {
                run.terminalEventCount = 1;
                if (!run.finalDeliveryAccepted) {
                    run.finalDeliveryFailureReason = clientDetached ? "client_detached" : "ack_pending";
                    run.terminalReason = clientDetached ? "delivery_pending_detached" : "delivery_pending";
                }
            } else {
                if (!run.finalDeliveryAccepted) {
                    run.finalDeliveryFailureReason = "final_emit_failed";
                    run.terminalReason = "delivery_failed";
                }
            }
            touchProgress(run);
            return true;
        }
    }

    boolean recordTerminalWithoutFinal(ChatRunExecutionContext context, String reason) {
        Run run = exactRun(context);
        if (!isOwner(run, context)) return false;
        synchronized (run.gate) {
            if (!isInFlight(run.status)) return false;
            if ("pending".equals(run.generationOutcome)) {
                run.generationOutcome = "error".equals(reason) ? "failed" : boundedReason(reason, "unknown");
            }
            run.terminalReason = boundedReason(reason, "unknown");
            touchProgress(run);
            return true;
        }
    }

    /**
     * Linearize one already-admitted durable terminal side effect with session
     * deletion. Writes are serialized independently of the control gate; deletion
     * fences new writes and waits for an admitted write to drain before finishing.
     */
    boolean runTerminalSideEffect(ChatRunExecutionContext context, Runnable action) {
        Run run = exactRun(context);
        if (!isOwner(run, context) || action == null) {
            return false;
        }
        synchronized (run.gate) {
            if (run.status != Status.COMMITTING || run.deletionFence || !leaseValid(run)) {
                return false;
            }
        }
        run.terminalWriteLock.lock();
        try {
            synchronized (run.gate) {
                if (exactRun(context) != run || runs.get(run.sessionId) != run
                        || run.status != Status.COMMITTING || run.deletionFence
                        || !leaseValid(run) || run.terminalWriteInProgress) return false;
                run.terminalWriteInProgress = true;
            }
            try {
                action.run();
                return true;
            } finally {
                synchronized (run.gate) {
                    run.terminalWriteInProgress = false;
                    touchProgress(run);
                }
            }
        } finally {
            run.terminalWriteLock.unlock();
            finishCancellation(run);
        }
    }

    boolean isCancellationRequested(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        return run == null || !isInFlight(run.status);
    }

    java.util.Optional<com.example.lms.ensemble.PreparedContextPacket> cachedContextPreparation(
            ChatRunExecutionContext context,String identity){
        Run run=exactRun(context);
        if(run==null)return java.util.Optional.empty();
        synchronized(run.gate){
            if(!isOwner(run,context)||!isInFlight(run.status)||!leaseValid(run)||run.deletionFence)return java.util.Optional.empty();
            return java.util.Optional.ofNullable(run.contextPreparation).filter(p->p.identity().equals(identity));
        }
    }
    void rememberContextPreparation(ChatRunExecutionContext context,com.example.lms.ensemble.PreparedContextPacket packet){
        Run run=exactRun(context);if(run==null||packet==null)return;
        synchronized(run.gate){
            if(isOwner(run,context)&&isInFlight(run.status)&&leaseValid(run)&&!run.deletionFence)
                run.contextPreparation=packet;
        }
    }

    boolean admitCall(ChatRunExecutionContext context, Runnable onAdmitted) {
        Run run = exactRun(context);
        if (run == null) return false;
        synchronized (run.gate) {
            if (!isInFlight(run.status) || !leaseValid(run)) {
                log.info("[LLM_CALL_BLOCKED] runHash={} boundary=application_admission reason=run_not_active",
                        context.redactedRunIdentity());
                return false;
            }
            onAdmitted.run();
            return true;
        }
    }

    boolean bindPersistedUserMessage(ChatRunExecutionContext context, Long id) {
        Run run = exactRun(context);
        if (run == null || id == null || id <= 0) return false;
        synchronized (run.gate) {
            if (!isOwner(run, context) || !isInFlight(run.status) || !leaseValid(run) || run.deletionFence
                    || (run.persistedUserMessageId != null && !run.persistedUserMessageId.equals(id))) return false;
            run.persistedUserMessageId = id;
            return true;
        }
    }
    Long persistedUserMessageId(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        if (run == null) return null;
        synchronized (run.gate) {
            return isOwner(run, context) && isInFlight(run.status) && leaseValid(run) && !run.deletionFence
                    ? run.persistedUserMessageId : null;
        }
    }
    boolean rememberUnderstandingIntent(ChatRunExecutionContext context,
            com.example.lms.service.understanding.UnderstandingCommitService.Intent intent) {
        Run run = exactRun(context);
        if (run == null || intent == null) return false;
        synchronized (run.gate) {
            if (!isOwner(run, context) || !isInFlight(run.status) || !leaseValid(run) || run.deletionFence
                    || !run.runId.equals(intent.originalRunId()) || !intent.scope().matchesSession(run.sessionId)
                    || !java.util.Objects.equals(run.persistedUserMessageId, intent.userId())
                    || (run.understandingIntent != null && !run.understandingIntent.equals(intent))) return false;
            run.understandingIntent = intent;
            return true;
        }
    }
    java.util.Optional<com.example.lms.service.understanding.UnderstandingCommitService.Intent> understandingIntent(
            ChatRunExecutionContext context) {
        Run run = exactRun(context);
        if (run == null) return java.util.Optional.empty();
        synchronized (run.gate) {
            return isOwner(run, context) && isInFlight(run.status) && leaseValid(run) && !run.deletionFence
                    ? java.util.Optional.ofNullable(run.understandingIntent) : java.util.Optional.empty();
        }
    }

    void observeCancellation(ChatRunExecutionContext context, String id, java.util.function.LongConsumer observer) {
        registerCancellationObserver(context, id, observer);
    }

    boolean registerCancellationObserver(ChatRunExecutionContext context, String id, java.util.function.LongConsumer observer) {
        Run run = exactRun(context);
        if (run == null || id == null || observer == null) return false;
        synchronized (run.gate) {
            if (run.cancellationAcceptedAtEpochMs > 0) {
                notifyCancellationObserver(observer, run.cancellationAcceptedAtEpochMs);
                return true;
            } else if (run.cancellationObservers.size() < 8 && isInFlight(run.status)) {
                return run.cancellationObservers.putIfAbsent(id, observer) == null;
            }
            return false;
        }
    }

    void removeCancellationObserver(ChatRunExecutionContext context, String id, java.util.function.LongConsumer expected) {
        Run run = exactRun(context);
        if (run == null) return;
        synchronized (run.gate) {
            run.cancellationObservers.remove(id, expected);
        }
    }

    private static void notifyCancellationObserver(java.util.function.LongConsumer observer, long at) {
        try { observer.accept(at); } catch (RuntimeException ignored) {
            // Observation cannot prevent exact-run cancellation.
        }
    }

    boolean permitsEmission(ChatRunExecutionContext context) {
        Run run = exactRun(context);
        return run != null && isInFlight(run.status) && leaseValid(run);
    }

    boolean registerProducerSink(ChatRunExecutionContext context,
                                 Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink) {
        Run run = exactRun(context);
        if (!isOwner(run, context) || sink == null || sink == run.sink) {
            return false;
        }
        synchronized (run.gate) {
            if (!isInFlight(run.status)) {
                sink.tryEmitComplete();
                return false;
            }
            if (run.producerSink != null && run.producerSink != sink) {
                return false;
            }
            run.producerSink = sink;
            touchProgress(run);
            return true;
        }
    }

    boolean unregisterProducerSink(ChatRunExecutionContext context,
                                   Sinks.Many<ServerSentEvent<ChatStreamEvent>> expectedSink) {
        Run run = exactRun(context);
        if (!isOwner(run, context) || expectedSink == null) {
            return false;
        }
        synchronized (run.gate) {
            if (run.producerSink != expectedSink) {
                return false;
            }
            run.producerSink = null;
            touchProgress(run);
            return true;
        }
    }

    boolean emitToProducer(ChatRunExecutionContext context,
                           ServerSentEvent<ChatStreamEvent> event) {
        Run run = exactRun(context);
        if (!isOwner(run, context) || event == null) {
            return false;
        }
        synchronized (run.gate) {
            if (!isInFlight(run.status) || run.producerSink == null) {
                return false;
            }
            boolean emitted = run.producerSink.tryEmitNext(event).isSuccess();
            if (emitted) {
                touchProgress(run);
            }
            return emitted;
        }
    }

    boolean registerCancellationHandle(ChatRunExecutionContext context, Disposable handle) {
        Run run = exactRun(context);
        if (handle == null) {
            return false;
        }
        if (run == null) {
            disposeSafely(handle, "run-missing");
            return false;
        }
        boolean accepted = false;
        boolean disposeRejected = false;
        synchronized (run.gate) {
            if (!isOwner(run, context)) {
                disposeRejected = true;
            } else if (run.cancellationHandle == handle) {
                accepted = run.status == Status.RUNNING || run.status == Status.COMMITTING;
                disposeRejected = run.status == Status.CANCELLING || run.status == Status.CANCELLED;
            } else if (run.status == Status.RUNNING && run.cancellationHandle == null) {
                run.cancellationHandle = handle;
                accepted = true;
            } else if (run.status == Status.RUNNING) {
                disposeRejected = true;
            } else if (run.status == Status.CANCELLING || run.status == Status.CANCELLED) {
                disposeRejected = true;
            }
            if (accepted) {
                touchProgress(run);
            }
        }
        if (!accepted && disposeRejected) {
            disposeSafely(handle, "register-rejected");
        }
        return accepted;
    }

    /**
     * Whether the given session currently has an active run.
     *
     * @param sessionId session identifier
     * @return true if running, false otherwise
     */
    public boolean isRunning(Long sessionId) {
        if (cluster != null) return currentRunToken(sessionId).flatMap(t -> describeExact(sessionId, t))
                .map(v -> isInFlight(v.status())).orElse(false);
        Run r = runs.get(sessionId);
        return r != null && isInFlight(r.status);
    }

    /** Opaque token for the current run; intended for authenticated HTTP state only. */
    public Optional<String> currentRunToken(Long sessionId) {
        if (cluster != null) return cluster.directory().currentToken(sessionId);
        Run run = sessionId == null ? null : runs.get(sessionId);
        return run == null ? Optional.empty() : Optional.of(run.runId);
    }

    public Optional<RunView> describeExact(Long sessionId, String runToken) {
        Run run = exactRun(sessionId, runToken);
        if (run == null && cluster != null) return cluster.describe(sessionId, runToken);
        if (run == null) {
            return Optional.empty();
        }
        boolean current = cluster == null ? runs.get(sessionId) == run
                : cluster.directory().currentToken(sessionId).map(run.runId::equals).orElse(false);
        synchronized (run.gate) {
            return Optional.of(new RunView(run.status, current, outcomeView(run)));
        }
    }

    public record RoutingView(com.example.lms.routing.RunRoutingSnapshot snapshot,
            List<com.example.lms.routing.RoutingInvocation.Observation> observations,RunOutcomeView outcome,
            com.example.lms.api.SettingsPlanProjection.View pipelineView) {}

    /** Caller must have passed the existing exact-run owner gate; never joins or captures a new policy. */
    public Optional<RoutingView> routingViewForAuthorizedExact(Long sessionId,String runToken) {
        Run run=exactRun(sessionId,runToken);
        if(run==null)return Optional.empty();
        com.example.lms.routing.RunRoutingSnapshot snapshot;
        List<com.example.lms.routing.RoutingInvocation.Observation> observations;
        synchronized(run.routingState){
            snapshot=run.routingSnapshot;
            if(snapshot==null)return Optional.empty();
            observations=run.routingInvocations.values().stream().map(com.example.lms.routing.RoutingInvocation::observation).toList();
        }
        synchronized(run.gate){return Optional.of(new RoutingView(snapshot,observations,outcomeView(run),run.routingPlan));}
    }

    /**
     * Whether the given session was explicitly cancelled and is still in the replay
     * registry.
     *
     * @param sessionId session identifier
     * @return true if the run is marked cancelled, false otherwise
     */
    public boolean isCancelled(Long sessionId) {
        if (cluster != null) return currentRunToken(sessionId).flatMap(t -> describeExact(sessionId, t))
                .map(v -> v.status() == Status.CANCELLED).orElse(false);
        Run r = runs.get(sessionId);
        return r != null && r.status == Status.CANCELLED;
    }

    private boolean cancelRun(
            Run run,
            Runnable beforeTerminal,
            boolean requireUnacknowledged,
            boolean allowCommitting) {
        return cancelRun(run, beforeTerminal, requireUnacknowledged, allowCommitting, List.of());
    }

    private boolean cancelRun(
            Run run,
            Runnable beforeTerminal,
            boolean requireUnacknowledged,
            boolean allowCommitting,
            List<ServerSentEvent<ChatStreamEvent>> terminalEvidence) {
        return cancelRun(run, beforeTerminal, requireUnacknowledged, allowCommitting, terminalEvidence, false);
    }

    private boolean cancelRun(
            Run run, Runnable beforeTerminal, boolean requireUnacknowledged,
            boolean allowCommitting, List<ServerSentEvent<ChatStreamEvent>> terminalEvidence,
            boolean requireNoInteractiveSubscribers) {
        if (run == null) {
            return false;
        }
        Disposable cancellationHandle;
        List<java.util.function.LongConsumer> cancellationObservers;
        synchronized (run.gate) {
            boolean cancellable = run.status == Status.RUNNING
                    || (allowCommitting && run.status == Status.COMMITTING);
            if (!cancellable || (requireUnacknowledged && run.clientAcknowledged)
                    || (requireNoInteractiveSubscribers && run.interactiveSubscribers != 0)) {
                return false;
            }
            run.status = Status.CANCELLING;
            run.cancellationAcceptedAtEpochMs = System.currentTimeMillis();
            log.info("[LLM_CANCEL_ACCEPTED] runHash={} observedAtEpochMs={} boundary=application_cancellation",
                    SafeRedactor.hashValue(run.runId), run.cancellationAcceptedAtEpochMs);
            cancellationObservers = List.copyOf(run.cancellationObservers.values());
            run.cancellationObservers.clear();
            run.cancellationTerminalEvidence = terminalEvidence;
            touchProgress(run);
            run.clientAcknowledgement.countDown();
            cancellationHandle = run.cancellationHandle;
            run.cancellationHandle = null;
        }
        try {
            cancellationObservers.forEach(observer ->
                    notifyCancellationObserver(observer, run.cancellationAcceptedAtEpochMs));
            if (cancellationHandle != null) {
                disposeSafely(cancellationHandle, "exact-cancel");
            }
            if (beforeTerminal != null) {
                try {
                    beforeTerminal.run();
                } catch (Throwable failure) {
                    log.warn("[AWX][chat-run] cancellation terminal action failed type={}",
                            failure.getClass().getSimpleName());
                }
            }
            synchronized (run.gate) {
                return run.status == Status.CANCELLING;
            }
        } finally {
            synchronized (run.gate) { run.cancellationCallbacksComplete = true; }
            finishCancellation(run);
        }
    }

    private void finishCancellation(Run run) {
        synchronized (run.gate) {
            if (run.status != Status.CANCELLING || run.terminalWriteInProgress
                    || !run.cancellationCallbacksComplete) return;
            List<ServerSentEvent<ChatStreamEvent>> terminalEvidence = run.cancellationTerminalEvidence;
            run.cancellationTerminalEvidence = List.of();
            run.status = Status.CANCELLED;
                if (!run.terminalEventClaimed) {
                    run.terminalEventClaimed = true;
                    boolean terminalEvidenceEmitted = false;
                    for (ServerSentEvent<ChatStreamEvent> event : terminalEvidence) {
                        terminalEvidenceEmitted |= run.sink.tryEmitNext(event).isSuccess();
                    }
                    if (terminalEvidence.isEmpty()) {
                        ChatStreamEvent.StatusSignal cancelledSignal = ChatStreamEvent.StatusSignal.of(
                                "stream", "cancelled", "stream cancelled", null, null, true);
                        ChatStreamEvent cancelledPayload = ChatStreamEvent.status(cancelledSignal);
                        ServerSentEvent<ChatStreamEvent> cancelledEvent =
                                ServerSentEvent.<ChatStreamEvent>builder(cancelledPayload)
                                        .event(cancelledPayload.type())
                                        .build();
                        terminalEvidenceEmitted = run.sink.tryEmitNext(cancelledEvent).isSuccess();
                    }
                    if (terminalEvidenceEmitted) {
                        run.terminalEventCount = 1;
                    }
                } else {
                    run.duplicateSuppressed++;
                }
                if ("pending".equals(run.generationOutcome)) {
                    run.generationOutcome = "cancelled";
                }
                run.terminalReason = run.deletionFence ? "session_deleted" : "cancelled";
                run.sink.tryEmitComplete();
        }
        run.cancellationComplete.countDown();
        scheduleTerminalEvictionOnce(run);
    }

    private boolean isOwner(Run run, ChatRunExecutionContext context) {
        return run != null && context != null && context.commitLease() == run.commitLease;
    }

    private void disposeSafely(Disposable handle, String reason) {
        try {
            handle.dispose();
        } catch (Throwable failure) {
            log.warn("[AWX][chat-run] cancellation handle dispose failed reason={} type={}",
                    reason, failure.getClass().getSimpleName());
        }
    }

    private ChatRunExecutionContext contextFor(Run run, boolean owner) {
        return new ChatRunExecutionContext(
                this,
                run.sessionId,
                run.runId,
                owner ? run.commitLease : null);
    }

    private Run exactRun(ChatRunExecutionContext context) {
        if (context == null || !context.belongsTo(this)) {
            return null;
        }
        return exactRun(context.sessionId(), context.runId());
    }

    private Run exactRun(Long sessionId, String runToken) {
        if (sessionId == null || runToken == null || runToken.isBlank()) {
            return null;
        }
        Run run = runsByToken.get(runToken);
        return run != null && sessionId.equals(run.sessionId) ? run : null;
    }

    private static boolean isInFlight(Status status) {
        return status == Status.RUNNING || status == Status.COMMITTING;
    }

    private static boolean isTerminal(Status status) {
        return status == Status.DONE || status == Status.CANCELLED;
    }

    private void touchProgress(Run run) {
        long nowMillis = clock.getAsLong();
        if (nowMillis > run.lastProgressMillis) {
            run.lastProgressMillis = nowMillis;
        }
    }

    private static long idleAgeMillis(long nowMillis, long lastProgressMillis) {
        if (nowMillis <= lastProgressMillis) {
            return 0L;
        }
        long elapsed = nowMillis - lastProgressMillis;
        return elapsed >= 0L ? elapsed : Long.MAX_VALUE;
    }

    private static RunOutcomeView outcomeView(Run run) {
        return new RunOutcomeView(
                SafeRedactor.hashValue(run.runId),
                run.generationOutcome,
                run.generationSucceeded,
                run.commitAttempted,
                run.commitAccepted,
                run.commitRejected,
                run.persisted,
                run.persistenceCount,
                run.finalEmitResult,
                run.finalDeliveryAccepted,
                run.finalDeliveryFailureReason,
                run.duplicateSuppressed,
                run.terminalReason,
                run.terminalEventCount,
                Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - run.outcomeStartedNanos)));
    }

    private static String boundedReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) return fallback;
        String normalized = reason.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private void scheduleTerminalEvictionOnce(Run expectedRun) {
        if (expectedRun == null) {
            return;
        }
        synchronized (expectedRun.gate) {
            if (!isTerminal(expectedRun.status) || expectedRun.terminalWriteInProgress
                    || expectedRun.terminalEvictionScheduled) {
                return;
            }
            expectedRun.terminalEvictionScheduled = true;
            expectedRun.evidenceExpiresAtMillis = clock.getAsLong() + Math.max(0L, ttlSeconds) * 1000L;
        }
        finishClusterOwner(expectedRun);
        long delaySeconds = expectedRun.deletionFence
                ? Math.max(1, ttlSeconds)
                : Math.max(0, ttlSeconds);
        evictor.schedule(() -> {
            runsByToken.remove(expectedRun.runId, expectedRun);
            runs.remove(expectedRun.sessionId, expectedRun);
        }, delaySeconds, TimeUnit.SECONDS);
    }
}

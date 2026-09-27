package com.example.lms.jobs;

import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in-memory job service used for local development and tests.
 * Not production ready: no persistence and process-local only.
 */
public class InMemoryJobService implements JobService {
    private static final int STATUS_CAPACITY = 4096;
    private static final long STATUS_TTL_MILLIS = 86_400_000L;
    private static final int CORE_WORKERS = 2;
    private static final int MAX_WORKERS = 4;
    private static final int WORK_QUEUE_CAPACITY = 64;
    private static final long KEEP_ALIVE_SECONDS = 30L;

    private final ConcurrentMap<String, StatusEntry> status = new ConcurrentHashMap<>();
    private final Object statusRetentionLock = new Object();
    private final Deque<StatusOrderEntry> statusOrder = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    // ThreadLocal(MDC/GuardContext) propagation is required because jobs may be
    // enqueued from a web request thread but executed on pooled workers.
    private final ExecutorService exec = new ContextAwareExecutorService(
            new ThreadPoolExecutor(
                    CORE_WORKERS,
                    MAX_WORKERS,
                    KEEP_ALIVE_SECONDS,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY),
                    r -> {
                        Thread t = new Thread(r, "job-exec-" + System.nanoTime());
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy()));
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong statusVersion = new AtomicLong();
    private final Clock clock;

    public InMemoryJobService() {
        this(Clock.systemUTC());
    }

    InMemoryJobService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String enqueue(String payload) {
        Objects.requireNonNull(payload, "payload");
        String id = Long.toHexString(System.currentTimeMillis()) + "-" + seq.incrementAndGet();
        putStatus(id, "PENDING");
        return id;
    }

    @Override
    public <T> void executeAsync(String jobId, Supplier<T> work, Consumer<T> onSuccess) {
        putStatus(jobId, "RUNNING");
        try {
            exec.submit(() -> {
                try {
                    T result = work.get();
                    if (completeSuccessfully(jobId) && onSuccess != null) {
                        onSuccess.accept(result);
                    }
                } catch (Throwable t) {
                    markTerminal(jobId, "FAILED");
                    traceSuppressed("executeAsync", t);
                }
            });
        } catch (RejectedExecutionException rejected) {
            markTerminal(jobId, "FAILED");
            traceSuppressed("executeAsyncRejected", rejected);
            throw rejected;
        }
    }

    @Override
    public String status(String jobId) {
        synchronized (statusRetentionLock) {
            evictExpiredLocked(clock.millis());
            StatusEntry entry = status.get(jobId);
            return entry == null ? "NOT_FOUND" : entry.state();
        }
    }

    @PreDestroy
    void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        exec.shutdownNow();
        failRunningJobsOnShutdown();
    }

    private void failRunningJobsOnShutdown() {
        for (String jobId : status.keySet()) {
            replaceStatus(jobId, "RUNNING", "FAILED");
        }
    }

    private void markTerminal(String jobId, String terminalState) {
        putStatus(jobId, terminalState);
    }

    private boolean completeSuccessfully(String jobId) {
        synchronized (statusRetentionLock) {
            long now = clock.millis();
            evictExpiredLocked(now);
            if (closed.get()) {
                putStatusLocked(jobId, "FAILED", now);
                return false;
            }
            putStatusLocked(jobId, "SUCCEEDED", now);
            return true;
        }
    }

    private void putStatus(String jobId, String state) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(state, "state");
        synchronized (statusRetentionLock) {
            long now = clock.millis();
            evictExpiredLocked(now);
            putStatusLocked(jobId, state, now);
        }
    }

    private boolean replaceStatus(String jobId, String expectedState, String replacementState) {
        synchronized (statusRetentionLock) {
            long now = clock.millis();
            evictExpiredLocked(now);
            StatusEntry current = status.get(jobId);
            if (current == null || !expectedState.equals(current.state())) {
                return false;
            }
            putStatusLocked(jobId, replacementState, now);
            return true;
        }
    }

    private void putStatusLocked(String jobId, String state, long now) {
        long version = statusVersion.incrementAndGet();
        status.put(jobId, new StatusEntry(state, version, now));
        statusOrder.addLast(new StatusOrderEntry(jobId, version, now));
        evictOverCapacityLocked();
    }

    private void evictExpiredLocked(long now) {
        var iterator = statusOrder.iterator();
        while (iterator.hasNext()) {
            StatusOrderEntry candidate = iterator.next();
            if (!isExpired(now, candidate.updatedAtEpochMs())) {
                continue;
            }
            iterator.remove();
            removeIfCurrentVersionLocked(candidate);
        }
    }

    private void evictOverCapacityLocked() {
        while (statusOrder.size() > STATUS_CAPACITY) {
            removeIfCurrentVersionLocked(statusOrder.removeFirst());
        }
    }

    private void removeIfCurrentVersionLocked(StatusOrderEntry candidate) {
        StatusEntry current = status.get(candidate.jobId());
        if (current != null && current.version() == candidate.stateVersion()) {
            status.remove(candidate.jobId(), current);
        }
    }

    private static boolean isExpired(long now, long updatedAtEpochMs) {
        return now >= updatedAtEpochMs && now - updatedAtEpochMs >= STATUS_TTL_MILLIS;
    }

    private record StatusEntry(String state, long version, long updatedAtEpochMs) { }

    private record StatusOrderEntry(String jobId, long stateVersion, long updatedAtEpochMs) { }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
        TraceStore.put("jobs.inMemory.suppressed." + safeStage, true);
        TraceStore.put("jobs.inMemory.suppressed." + safeStage + ".errorType", errorType);
    }
}

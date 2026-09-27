package com.example.lms.harmony;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the capped shared scheduler used by Harmony score SSE streams. */
@Component
public final class HarmonySseRuntime {

    private static final int MAX_STREAMS = 32;
    private static final int SCHEDULER_THREADS = 2;
    private static final long STREAM_PERIOD_SECONDS = 30L;

    private final ScheduledExecutorService scheduler;
    private final Semaphore permits;
    private final Set<StreamLease> activeLeases = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public HarmonySseRuntime() {
        this(newScheduler(), MAX_STREAMS);
    }

    HarmonySseRuntime(ScheduledExecutorService scheduler, int maxStreams) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        int boundedStreams = Math.min(MAX_STREAMS, Math.max(1, maxStreams));
        this.permits = new Semaphore(boundedStreams, true);
    }

    public Optional<StreamLease> open(Runnable tick) {
        Objects.requireNonNull(tick, "tick");
        if (shutdown.get() || !permits.tryAcquire()) {
            return Optional.empty();
        }

        StreamLease lease = new StreamLease();
        activeLeases.add(lease);
        if (shutdown.get()) {
            lease.close();
            return Optional.empty();
        }

        try {
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    () -> lease.runTick(tick),
                    0L,
                    STREAM_PERIOD_SECONDS,
                    TimeUnit.SECONDS);
            lease.attach(future);
            if (shutdown.get()) {
                lease.close();
                return Optional.empty();
            }
            return Optional.of(lease);
        } catch (RuntimeException rejected) {
            lease.close();
            return Optional.empty();
        }
    }

    int activeCount() {
        return activeLeases.size();
    }

    @PreDestroy
    void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        List.copyOf(activeLeases).forEach(StreamLease::close);
        scheduler.shutdownNow();
    }

    private void release(StreamLease lease) {
        if (activeLeases.remove(lease)) {
            permits.release();
        }
    }

    public final class StreamLease implements AutoCloseable {
        private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

        private void attach(ScheduledFuture<?> scheduledFuture) {
            Objects.requireNonNull(scheduledFuture, "scheduledFuture");
            if (!future.compareAndSet(null, scheduledFuture)) {
                scheduledFuture.cancel(false);
                throw new IllegalStateException("Harmony SSE future already attached");
            }
            cancelFutureIfClosed();
        }

        private void runTick(Runnable tick) {
            if (closed.get()) {
                return;
            }
            try {
                tick.run();
            } catch (RuntimeException terminalFailure) {
                close();
            } catch (Error terminalError) {
                close();
                throw terminalError;
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            cancelFutureIfClosed();
            release(this);
        }

        private void cancelFutureIfClosed() {
            ScheduledFuture<?> scheduledFuture = future.get();
            if (closed.get() && scheduledFuture != null
                    && cancellationRequested.compareAndSet(false, true)) {
                scheduledFuture.cancel(false);
            }
        }
    }

    private static ScheduledExecutorService newScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                SCHEDULER_THREADS,
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return Executors.unconfigurableScheduledExecutorService(executor);
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "harmony-sse-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

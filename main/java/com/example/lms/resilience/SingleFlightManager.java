package com.example.lms.resilience;

import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name = "cache.singleflight.enabled", havingValue = "true", matchIfMissing = false)
public class SingleFlightManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SingleFlightManager.class);
    private static final long DEFAULT_TIMEOUT_MS = 15_000L;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ConcurrentHashMap<String, Flight> inflight = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long timeoutMs;
    private final ExecutorService executor;

    public SingleFlightManager() {
        this(DEFAULT_TIMEOUT_MS, newExecutor());
    }

    @Autowired
    public SingleFlightManager(
            @Value("${cache.singleflight.timeout-ms:15000}") long configuredTimeoutMs) {
        this(configuredTimeoutMs, newExecutor());
    }

    SingleFlightManager(long timeoutMs, ExecutorService executor) {
        this.timeoutMs = Math.max(1L, timeoutMs);
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public <T> T run(String key, Callable<T> task) throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        rejectIfClosed();

        Flight candidate = new Flight();
        FutureTask<Void> owner = new FutureTask<>(ContextPropagation.wrap(
                () -> executeOwner(key, candidate, task)), null);
        candidate.setOwner(owner);

        Flight flight = inflight.putIfAbsent(key, candidate);
        if (flight == null) {
            flight = candidate;
            if (closed.get()) {
                cancelForClose(key, candidate);
                throw closedRejection();
            }
            try {
                executor.execute(owner);
            } catch (RejectedExecutionException rejected) {
                candidate.result.completeExceptionally(rejected);
                inflight.remove(key, candidate);
                candidate.cancelOwner();
                throw rejected;
            }
        }

        return await(key, flight);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        inflight.forEach(this::cancelForClose);
        executor.shutdownNow();
    }

    private <T> void executeOwner(String key, Flight flight, Callable<T> task) {
        try {
            flight.result.complete(task.call());
        } catch (Throwable failure) {
            traceSkipped("single_flight_task", failure);
            flight.result.completeExceptionally(failure);
        } finally {
            inflight.remove(key, flight);
        }
    }

    private <T> T await(String key, Flight flight) throws Exception {
        try {
            return cast(flight.result.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException elapsed) {
            TimeoutException sharedTimeout = new TimeoutException(
                    "Single-Flight timed out after " + timeoutMs + " ms");
            if (flight.result.completeExceptionally(sharedTimeout)) {
                inflight.remove(key, flight);
                flight.cancelOwner();
            }
            return completedValue(flight.result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            return rethrow(failed.getCause());
        }
    }

    private void cancelForClose(String key, Flight flight) {
        flight.result.completeExceptionally(new CancellationException("Single-Flight manager closed"));
        inflight.remove(key, flight);
        flight.cancelOwner();
    }

    private void rejectIfClosed() {
        if (closed.get()) {
            throw closedRejection();
        }
    }

    private static RejectedExecutionException closedRejection() {
        return new RejectedExecutionException("Single-Flight manager is closed");
    }

    private static <T> T completedValue(CompletableFuture<Object> result) throws Exception {
        try {
            return cast(result.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            return rethrow(failed.getCause());
        }
    }

    private static <T> T rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        throw new Exception(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static ExecutorService newExecutor() {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "single-flight-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(2, factory);
    }

    private static void traceSkipped(String stage, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        LOG.debug("[AWX][single-flight] trace skipped stage={} errorType={}",
                safeStage, safeErrorType);
    }

    private static final class Flight {
        private final CompletableFuture<Object> result = new CompletableFuture<>();
        private final AtomicReference<Future<?>> owner = new AtomicReference<>();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();

        private void setOwner(Future<?> ownerFuture) {
            if (!owner.compareAndSet(null, ownerFuture)) {
                throw new IllegalStateException("Single-Flight owner already assigned");
            }
            if (cancellationRequested.get()) {
                ownerFuture.cancel(true);
            }
        }

        private void cancelOwner() {
            cancellationRequested.set(true);
            Future<?> ownerFuture = owner.get();
            if (ownerFuture != null) {
                ownerFuture.cancel(true);
            }
        }
    }
}

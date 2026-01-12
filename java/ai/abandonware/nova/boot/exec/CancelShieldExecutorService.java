package ai.abandonware.nova.boot.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * ExecutorService wrapper that:
 * <ul>
 *   <li>propagates MDC/GuardContext/TraceStore across worker threads</li>
 *   <li>returns {@link CancelShieldFuture} so callers cannot interrupt worker threads via {@code cancel(true)}</li>
 * </ul>
 *
 * <p>
 * This is an operational hardening layer for bootRun/soak runs where
 * interrupt-poisoning can destabilize pooled executors.
 */
public final class CancelShieldExecutorService extends ContextPropagatingExecutorService {

    private final String owner;

    public CancelShieldExecutorService(ExecutorService delegate, String owner) {
        super(Objects.requireNonNull(delegate, "delegate"));
        this.owner = (owner == null || owner.isBlank()) ? "executor" : owner;
    }

    private <T> Future<T> shield(Future<T> f) {
        if (f == null) {
            return null;
        }
        if (f instanceof CancelShieldFuture) {
            return f;
        }
        return new CancelShieldFuture<>(f, owner);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        // Context propagation is already handled by ContextPropagatingExecutorService.
        return shield(super.submit(task));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return shield(super.submit(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return shield(super.submit(task, result));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        List<Future<T>> out = super.invokeAll(tasks);
        if (out == null || out.isEmpty()) {
            return out;
        }
        List<Future<T>> shielded = new ArrayList<>(out.size());
        for (Future<T> f : out) {
            shielded.add(shield(f));
        }
        return shielded;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        // NOTE:
        // JDK ExecutorService.invokeAll(timeout) cancels unfinished tasks with cancel(true)
        // on timeout, which attempts to interrupt pooled worker threads. In bootRun/soak
        // this "interrupt poisoning" can cascade into WebClient/Reactive chains.
        //
        // To fail-soft and keep workers healthy, we re-implement invokeAll(timeout)
        // with cancel(false) only.

        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(unit, "unit");

        if (tasks.isEmpty()) {
            return List.of();
        }

        long timeoutNs = unit.toNanos(timeout);
        final long deadlineNs = System.nanoTime() + timeoutNs;

        // Use completion-service polling to treat timeout as a budget window
        // (avoid invokeAll(timeout) -> cancel(true) interrupt cascade).
        ExecutorCompletionService<T> ecs = new ExecutorCompletionService<>(this);

        // Return futures in submission order (invokeAll contract).
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            if (task == null) {
                throw new NullPointerException("task");
            }
            Future<T> raw = ecs.submit(task);
            futures.add(shield(raw));
        }

        int remaining = futures.size();
        boolean timedOut = false;
        boolean interrupted = false;
        try {
            while (remaining > 0) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) {
                    timedOut = true;
                    break;
                }
                Future<T> done = ecs.poll(remainingNs, TimeUnit.NANOSECONDS);
                if (done == null) {
                    timedOut = true;
                    break;
                }
                remaining--; // one task completed (success, failure, or cancellation)
            }
        } catch (InterruptedException ie) {
            interrupted = true;
            throw ie;
        } finally {
            if (timedOut || interrupted) {
                // Cancel remaining tasks WITHOUT interrupt.
                for (Future<T> f : futures) {
                    if (f == null || f.isDone()) {
                        continue;
                    }
                    try {
                        f.cancel(false);
                    } catch (Throwable ignore) {
                        // fail-soft
                    }
                }
            }
        }

        return futures;
    }
}

package ai.abandonware.nova.boot.exec;

import com.example.lms.search.TraceStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private static final int DEFAULT_INVOKE_ALL_MAX_INFLIGHT = 8;

    private final String owner;
    private final int invokeAllMaxInflight;

    public CancelShieldExecutorService(ExecutorService delegate, String owner) {
        this(delegate, owner, DEFAULT_INVOKE_ALL_MAX_INFLIGHT);
    }

    public CancelShieldExecutorService(ExecutorService delegate, String owner, int invokeAllMaxInflight) {
        super(Objects.requireNonNull(delegate, "delegate"));
        this.owner = (owner == null || owner.isBlank()) ? "executor" : owner;

        int v = invokeAllMaxInflight;
        if (v <= 0) {
            v = DEFAULT_INVOKE_ALL_MAX_INFLIGHT;
        }
        // Avoid pathological values.
        if (v < 1) {
            v = 1;
        }
        if (v > 1024) {
            v = 1024;
        }
        this.invokeAllMaxInflight = v;
    }

    public int getInvokeAllMaxInflight() {
        return invokeAllMaxInflight;
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

        // Debug/observability: timed invokeAll is a high-risk cancellation surface.
        try {
            TraceStore.inc("ops.cancelShield.invokeAll.timeout.used");
            TraceStore.put("ops.cancelShield.invokeAll.timeout.owner", owner);
            TraceStore.put("ops.cancelShield.invokeAll.timeout.tasks", tasks.size());
            TraceStore.put("ops.cancelShield.invokeAll.timeout.timeout", timeout);
            TraceStore.put("ops.cancelShield.invokeAll.timeout.unit", String.valueOf(unit));
        } catch (Throwable ignore) {
            // best-effort
        }

        // cancel(false) does not stop already-running tasks; to reduce background work
        // we "stage" submission and keep a bounded number of in-flight tasks.
        final int maxInflight = Math.max(1, Math.min(invokeAllMaxInflight, tasks.size()));

        long timeoutNs = unit.toNanos(timeout);
        if (timeoutNs < 0L) {
            timeoutNs = 0L;
        }
        final long deadlineNs = System.nanoTime() + timeoutNs;

        // Observability: surface max inflight as a stable tuning knob.
        try {
            TraceStore.put("ops.cancelShield.invokeAll.timeout.maxInflight", maxInflight);
        } catch (Throwable ignore) {
            // best-effort
        }

        // We must return one Future per task (invokeAll contract), even if we decide
        // not to submit all of them before the timeout expires.
        final BlockingQueue<NotifyingFutureTask<T>> doneQ = new LinkedBlockingQueue<>();
        final List<NotifyingFutureTask<T>> ftasks = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            if (task == null) {
                throw new NullPointerException("task");
            }
            // Wrap for MDC/GuardContext/TraceStore propagation.
            ftasks.add(new NotifyingFutureTask<>(wrapCallable(task), doneQ));
        }

        final List<Future<T>> futures = new ArrayList<>(ftasks.size());
        for (NotifyingFutureTask<T> ft : ftasks) {
            futures.add(shield(ft));
        }

        int submitted = 0;
        int completed = 0;
        int active = 0;
        boolean timedOut = false;
        boolean interrupted = false;
        int cancelAttempted = 0;
        int cancelSucceeded = 0;
        int pendingBeforeCancel = 0;

        try {
            // Initial burst (bounded)
            while (submitted < ftasks.size() && active < maxInflight) {
                NotifyingFutureTask<T> ft = ftasks.get(submitted);
                try {
                    delegate.execute(ft);
                } catch (RejectedExecutionException rex) {
                    // If we can't schedule, cancel softly so invokeAll doesn't hang.
                    ft.cancel(false);
                }
                submitted++;
                active++;
            }

            // Drain completions and keep pipeline full (until timeout budget is spent)
            while (completed < ftasks.size()) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) {
                    timedOut = true;
                    break;
                }
                NotifyingFutureTask<T> done = doneQ.poll(remainingNs, TimeUnit.NANOSECONDS);
                if (done == null) {
                    timedOut = true;
                    break;
                }
                completed++;
                active = Math.max(0, active - 1);

                // Top-up submission after each completion (bounded)
                while (submitted < ftasks.size() && active < maxInflight) {
                    NotifyingFutureTask<T> ft = ftasks.get(submitted);
                    try {
                        delegate.execute(ft);
                    } catch (RejectedExecutionException rex) {
                        ft.cancel(false);
                    }
                    submitted++;
                    active++;
                }
            }
        } catch (InterruptedException ie) {
            interrupted = true;
            throw ie;
        } finally {
            if (timedOut || interrupted) {
                // Snapshot pending count before we attempt soft-cancel.
                try {
                    int p = 0;
                    for (Future<T> f : futures) {
                        if (f != null && !f.isDone()) {
                            p++;
                        }
                    }
                    pendingBeforeCancel = p;
                    TraceStore.put("ops.cancelShield.invokeAll.timeout.pendingBeforeCancel", p);
                } catch (Throwable ignore) {
                    // best-effort
                }

                // Cancel remaining tasks WITHOUT interrupt.
                for (Future<T> f : futures) {
                    if (f == null || f.isDone()) {
                        continue;
                    }
                    try {
                        cancelAttempted++;
                        if (f.cancel(false)) {
                            cancelSucceeded++;
                        }
                    } catch (Throwable ignore) {
                        // fail-soft
                    }
                }
            }

            int remaining = Math.max(0, ftasks.size() - completed);

            // Persist coarse-grained outcome keys for RCA (stable KPI surface).
            try {
                TraceStore.put("ops.cancelShield.invokeAll.timeout.timedOut", timedOut);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.interrupted", interrupted);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.submitted", submitted);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.completed", completed);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.remaining", remaining);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.cancelAttempted", cancelAttempted);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.cancelSucceeded", cancelSucceeded);
            } catch (Throwable ignore) {
                // best-effort
            }

            // Append an event (bounded by TraceStore clipper in renderers).
            try {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("tsMs", System.currentTimeMillis());
                ev.put("owner", owner);
                ev.put("tasks", futures.size());
                ev.put("timeout", timeout);
                ev.put("unit", String.valueOf(unit));
                ev.put("maxInflight", maxInflight);
                ev.put("submitted", submitted);
                ev.put("completed", completed);
                ev.put("timedOut", timedOut);
                ev.put("interrupted", interrupted);
                ev.put("pendingBeforeCancel", pendingBeforeCancel);
                ev.put("cancelAttempted", cancelAttempted);
                ev.put("cancelSucceeded", cancelSucceeded);
                ev.put("remaining", remaining);
                TraceStore.append("ops.cancelShield.invokeAll.timeout.events", ev);
            } catch (Throwable ignore) {
                // best-effort
            }
        }

        return futures;
    }

    /**
     * FutureTask variant that notifies a completion queue on any terminal state
     * (success, failure, or cancellation).
     */
    private static final class NotifyingFutureTask<V> extends FutureTask<V> {
        private final BlockingQueue<NotifyingFutureTask<V>> doneQ;

        private NotifyingFutureTask(Callable<V> callable, BlockingQueue<NotifyingFutureTask<V>> doneQ) {
            super(callable);
            this.doneQ = doneQ;
        }

        @Override
        protected void done() {
            if (doneQ != null) {
                // best-effort; queue is unbounded but offer may still fail if interrupted
                try {
                    doneQ.offer(this);
                } catch (Throwable ignore) {
                    // best-effort
                }
            }
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        try {
            return doInvokeAny(tasks, false, 0L);
        } catch (TimeoutException te) {
            // not possible in non-timed variant
            throw new AssertionError(te);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit, "unit");
        long nanos = unit.toNanos(timeout);
        return doInvokeAny(tasks, true, nanos);
    }

    /**
     * JDK's default invokeAny cancels unfinished tasks using cancel(true), which can attempt to
     * interrupt pooled worker threads. In this wrapper we implement invokeAny ourselves and cancel
     * remaining tasks using cancel(false) only to prevent interrupt-poisoning.
     */
    private <T> T doInvokeAny(Collection<? extends Callable<T>> tasks, boolean timed, long nanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(tasks, "tasks");
        int ntasks = tasks.size();
        if (ntasks == 0) {
            throw new IllegalArgumentException("tasks is empty");
        }

        // NOTE: This implementation is based on JDK AbstractExecutorService#doInvokeAny,
        // but differs in one critical aspect: we NEVER use cancel(true).
        //
        // Why: cancel(true) can interrupt pooled worker threads and cause
        // interrupt-poisoning cascades (WebClient/Reactive chains).
        //
        // Trade-off: losers may continue until their own timeouts. To reduce
        // background work, we submit tasks gradually (not all at once).

        // Debug/observability: invokeAny is another high-risk cancellation surface.
        try {
            TraceStore.inc("ops.cancelShield.invokeAny.used");
            TraceStore.put("ops.cancelShield.invokeAny.owner", owner);
            TraceStore.put("ops.cancelShield.invokeAny.timed", timed);
            if (timed) {
                TraceStore.put("ops.cancelShield.invokeAny.timeoutNs", nanos);
            }
            TraceStore.put("ops.cancelShield.invokeAny.tasks", ntasks);
        } catch (Throwable ignore) {
            // best-effort
        }

        ExecutorCompletionService<T> ecs = new ExecutorCompletionService<>(this);
        List<Future<T>> futures = new ArrayList<>(ntasks);

        int submitted = 0;
        int cancelAttempted = 0;
        int cancelSucceeded = 0;
        boolean outcomeTimedOut = false;
        boolean outcomeSuccess = false;

        ExecutionException lastEx = null;
        long deadlineNs = 0L;
        if (timed) {
            deadlineNs = System.nanoTime() + Math.max(0L, nanos);
        }

        int active = 0;
        int remaining = ntasks;
        java.util.Iterator<? extends Callable<T>> it = tasks.iterator();

        try {
            // Submit the first task to get things started.
            Callable<T> first = it.next();
            if (first == null) {
                throw new NullPointerException("task");
            }
            futures.add(shield(ecs.submit(first)));
            submitted++;
            active = 1;
            remaining = ntasks - 1;

            for (;;) {
                Future<T> f = ecs.poll();
                if (f == null) {
                    if (remaining > 0) {
                        // Submit another task (staged submission reduces wasted background work).
                        Callable<T> next = it.next();
                        if (next == null) {
                            throw new NullPointerException("task");
                        }
                        futures.add(shield(ecs.submit(next)));
                        submitted++;
                        remaining--;
                        active++;
                        continue;
                    }

                    if (active == 0) {
                        break;
                    }

                    if (timed) {
                        long waitNs = deadlineNs - System.nanoTime();
                        if (waitNs <= 0L) {
                            outcomeTimedOut = true;
                            throw new TimeoutException();
                        }
                        f = ecs.poll(waitNs, TimeUnit.NANOSECONDS);
                        if (f == null) {
                            outcomeTimedOut = true;
                            throw new TimeoutException();
                        }
                    } else {
                        f = ecs.take();
                    }
                }

                if (f != null) {
                    active--;
                    try {
                        T result = f.get();
                        outcomeSuccess = true;
                        return result;
                    } catch (ExecutionException ee) {
                        lastEx = ee;
                    } catch (RuntimeException re) {
                        lastEx = new ExecutionException(re);
                    }
                }
            }

            // All tasks failed.
            if (lastEx != null) {
                throw lastEx;
            }
            throw new ExecutionException(new IllegalStateException("invokeAny: no task completed successfully"));
        } finally {
            // Always cancel remaining tasks WITHOUT interrupt.
            for (Future<T> f : futures) {
                if (f == null || f.isDone()) {
                    continue;
                }
                try {
                    cancelAttempted++;
                    if (f.cancel(false)) {
                        cancelSucceeded++;
                    }
                } catch (Throwable ignore) {
                    // fail-soft
                }
            }

            // Outcome keys for RCA.
            try {
                TraceStore.put("ops.cancelShield.invokeAny.submitted", submitted);
                TraceStore.put("ops.cancelShield.invokeAny.success", outcomeSuccess);
                TraceStore.put("ops.cancelShield.invokeAny.timedOut", outcomeTimedOut);
                TraceStore.put("ops.cancelShield.invokeAny.cancelAttempted", cancelAttempted);
                TraceStore.put("ops.cancelShield.invokeAny.cancelSucceeded", cancelSucceeded);
            } catch (Throwable ignore) {
                // best-effort
            }

            // Append an event (bounded by TraceStore clipper in renderers).
            try {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("tsMs", System.currentTimeMillis());
                ev.put("owner", owner);
                ev.put("tasks", ntasks);
                ev.put("timed", timed);
                if (timed) {
                    ev.put("timeoutNs", nanos);
                }
                ev.put("submitted", submitted);
                ev.put("success", outcomeSuccess);
                ev.put("timedOut", outcomeTimedOut);
                ev.put("cancelAttempted", cancelAttempted);
                ev.put("cancelSucceeded", cancelSucceeded);
                if (lastEx != null) {
                    ev.put("lastEx", lastEx.getClass().getName());
                }
                TraceStore.append("ops.cancelShield.invokeAny.events", ev);
            } catch (Throwable ignore) {
                // best-effort
            }
        }
    }
}

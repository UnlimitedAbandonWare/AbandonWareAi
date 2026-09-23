package ai.abandonware.nova.boot.exec;

import com.example.lms.cfvm.CfvmFailureRecoveryHandler;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

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
import java.util.function.Supplier;

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
    private static final System.Logger LOG = System.getLogger(CancelShieldExecutorService.class.getName());

    private final String owner;
    private final int invokeAllMaxInflight;
    private final Supplier<DebugEventStore> debugEventStoreSupplier;
    private final Supplier<CfvmFailureRecoveryHandler> recoveryHandlerSupplier;

    public CancelShieldExecutorService(ExecutorService delegate, String owner) {
        this(delegate, owner, DEFAULT_INVOKE_ALL_MAX_INFLIGHT);
    }

    public CancelShieldExecutorService(ExecutorService delegate, String owner, int invokeAllMaxInflight) {
        this(delegate, owner, invokeAllMaxInflight, null, null);
    }

    public CancelShieldExecutorService(ExecutorService delegate,
                                       String owner,
                                       int invokeAllMaxInflight,
                                       Supplier<DebugEventStore> debugEventStoreSupplier,
                                       Supplier<CfvmFailureRecoveryHandler> recoveryHandlerSupplier) {
        super(Objects.requireNonNull(delegate, "delegate"));
        this.owner = safeOwner(owner);
        this.debugEventStoreSupplier = debugEventStoreSupplier;
        this.recoveryHandlerSupplier = recoveryHandlerSupplier;

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
        return new CancelShieldFuture<>(f, owner, debugEventStoreSupplier, recoveryHandlerSupplier);
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
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) {
            return List.of();
        }

        try {
            TraceStore.inc("ops.cancelShield.invokeAll.used");
            TraceStore.put("ops.cancelShield.invokeAll.ownerHash", SafeRedactor.hashValue(owner));
            TraceStore.put("ops.cancelShield.invokeAll.ownerLength", owner == null ? 0 : owner.length());
            TraceStore.put("ops.cancelShield.invokeAll.tasks", tasks.size());
        } catch (Throwable telemetryError) {
            traceTelemetrySkipped("invokeAll.used", telemetryError);
        }

        final BlockingQueue<NotifyingFutureTask<T>> doneQ = new LinkedBlockingQueue<>();
        final List<NotifyingFutureTask<T>> ftasks = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            if (task == null) {
                throw new NullPointerException("task");
            }
            ftasks.add(new NotifyingFutureTask<>(wrapCallable(task), doneQ));
        }

        final List<Future<T>> futures = new ArrayList<>(ftasks.size());
        for (NotifyingFutureTask<T> ft : ftasks) {
            futures.add(shield(ft));
        }

        int submitted = 0;
        int completed = 0;
        boolean interrupted = false;
        int cancelAttempted = 0;
        int cancelSucceeded = 0;

        try {
            for (NotifyingFutureTask<T> ft : ftasks) {
                try {
                    delegate.execute(ft);
                } catch (RejectedExecutionException rex) {
                    TraceStore.inc("ops.cancelShield.invokeAll.rejected.count"); TraceStore.put("ops.cancelShield.invokeAll.rejected.stage", "untimed");
                    ft.cancel(false);
                }
                submitted++;
            }
            while (completed < ftasks.size()) {
                doneQ.take();
                completed++;
            }
        } catch (InterruptedException ie) {
            interrupted = true;
            throw ie;
        } finally {
            if (interrupted) {
                for (Future<T> f : futures) {
                    if (f == null || f.isDone()) {
                        continue;
                    }
                    try {
                        cancelAttempted++;
                        if (f.cancel(false)) {
                            cancelSucceeded++;
                        }
                    } catch (Throwable cancelError) {
                        traceTelemetrySkipped("invokeAll.interrupted.cancel", cancelError);
                    }
                }

                try {
                    TraceStore.inc("ops.cancelShield.invokeAll.interrupted.used");
                    TraceStore.put("ops.cancelShield.invokeAll.interrupted.ownerHash", SafeRedactor.hashValue(owner));
                    TraceStore.put("ops.cancelShield.invokeAll.interrupted.ownerLength", owner == null ? 0 : owner.length());
                    TraceStore.put("ops.cancelShield.invokeAll.interrupted.tasks", ftasks.size());
                    TraceStore.put("ops.cancelShield.invokeAll.interrupted.submitted", submitted);
                    TraceStore.put("ops.cancelShield.invokeAll.interrupted.completed", completed);
                    TraceStore.put("ops.cancelShield.invokeAll.interrupted.cancelAttempted", cancelAttempted);
                    TraceStore.put("ops.cancelShield.invokeAll.interrupted.cancelSucceeded", cancelSucceeded);
                } catch (Throwable telemetryError) {
                    traceTelemetrySkipped("invokeAll.interrupted.outcome", telemetryError);
                }

                try {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("tsMs", System.currentTimeMillis());
                    ev.put("ownerHash", SafeRedactor.hashValue(owner));
                    ev.put("ownerLength", owner == null ? 0 : owner.length());
                    ev.put("tasks", ftasks.size());
                    ev.put("submitted", submitted);
                    ev.put("completed", completed);
                    ev.put("interrupted", true);
                    ev.put("cancelAttempted", cancelAttempted);
                    ev.put("cancelSucceeded", cancelSucceeded);
                    TraceStore.append("ops.cancelShield.invokeAll.interrupted.events", ev);
                } catch (Throwable telemetryError) {
                    traceTelemetrySkipped("invokeAll.interrupted.event", telemetryError);
                }
            }
        }

        return futures;
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
            TraceStore.put("ops.cancelShield.invokeAll.timeout.ownerHash", SafeRedactor.hashValue(owner));
            TraceStore.put("ops.cancelShield.invokeAll.timeout.ownerLength", owner == null ? 0 : owner.length());
            TraceStore.put("ops.cancelShield.invokeAll.timeout.tasks", tasks.size());
            TraceStore.put("ops.cancelShield.invokeAll.timeout.timeout", timeout);
            TraceStore.put("ops.cancelShield.invokeAll.timeout.unit", String.valueOf(unit));
        } catch (Throwable telemetryError) {
            traceTelemetrySkipped("invokeAll.timeout.used", telemetryError);
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
        } catch (Throwable telemetryError) {
            traceTelemetrySkipped("invokeAll.timeout.maxInflight", telemetryError);
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
            if (timeoutNs <= 0L) {
                timedOut = true;
            } else {
                // Initial burst (bounded)
                while (submitted < ftasks.size() && active < maxInflight) {
                    NotifyingFutureTask<T> ft = ftasks.get(submitted);
                    try {
                        delegate.execute(ft);
                    } catch (RejectedExecutionException rex) {
                        TraceStore.inc("ops.cancelShield.invokeAll.rejected.count"); TraceStore.put("ops.cancelShield.invokeAll.rejected.stage", "timed");
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
                            TraceStore.inc("ops.cancelShield.invokeAll.rejected.count"); TraceStore.put("ops.cancelShield.invokeAll.rejected.stage", "timed");
                            ft.cancel(false);
                        }
                        submitted++;
                        active++;
                    }
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
                } catch (Throwable telemetryError) {
                    traceTelemetrySkipped("invokeAll.timeout.pendingBeforeCancel", telemetryError);
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
                    } catch (Throwable cancelError) {
                        traceTelemetrySkipped("invokeAll.timeout.cancel", cancelError);
                    }
                }
            }

            int remaining = Math.max(0, ftasks.size() - completed);

            // Persist coarse-grained outcome keys for RCA (stable KPI surface).
            try {
                TraceStore.put("ops.cancelShield.invokeAll.timeout.ownerHash", SafeRedactor.hashValue(owner));
                TraceStore.put("ops.cancelShield.invokeAll.timeout.ownerLength", owner == null ? 0 : owner.length());
                TraceStore.put("ops.cancelShield.invokeAll.timeout.timedOut", timedOut);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.interrupted", interrupted);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.submitted", submitted);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.completed", completed);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.remaining", remaining);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.cancelAttempted", cancelAttempted);
                TraceStore.put("ops.cancelShield.invokeAll.timeout.cancelSucceeded", cancelSucceeded);
                if (timedOut) {
                    TraceStore.put("timeout.stage", "cancel_shield_invoke_all");
                }
            } catch (Throwable telemetryError) {
                traceTelemetrySkipped("invokeAll.timeout.outcome", telemetryError);
            }

            // Append an event (bounded by TraceStore clipper in renderers).
            try {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("tsMs", System.currentTimeMillis());
                ev.put("ownerHash", SafeRedactor.hashValue(owner));
                ev.put("ownerLength", owner == null ? 0 : owner.length());
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
            } catch (Throwable telemetryError) {
                traceTelemetrySkipped("invokeAll.timeout.event", telemetryError);
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
                } catch (Throwable queueError) {
                    traceTelemetrySkipped("notifyingFuture.done", queueError);
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
            TraceStore.put("ops.cancelShield.invokeAny.ownerHash", SafeRedactor.hashValue(owner));
            TraceStore.put("ops.cancelShield.invokeAny.ownerLength", owner == null ? 0 : owner.length());
            TraceStore.put("ops.cancelShield.invokeAny.timed", timed);
            if (timed) {
                TraceStore.put("ops.cancelShield.invokeAny.timeoutNs", nanos);
            }
            TraceStore.put("ops.cancelShield.invokeAny.tasks", ntasks);
        } catch (Throwable telemetryError) {
            traceTelemetrySkipped("invokeAny.used", telemetryError);
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
            if (timed && nanos <= 0L) {
                outcomeTimedOut = true;
                throw new TimeoutException();
            }

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
                } catch (Throwable cancelError) {
                    traceTelemetrySkipped("invokeAny.cancel", cancelError);
                }
            }

            // Outcome keys for RCA.
            try {
                TraceStore.put("ops.cancelShield.invokeAny.submitted", submitted);
                TraceStore.put("ops.cancelShield.invokeAny.success", outcomeSuccess);
                TraceStore.put("ops.cancelShield.invokeAny.timedOut", outcomeTimedOut);
                TraceStore.put("ops.cancelShield.invokeAny.cancelAttempted", cancelAttempted);
                TraceStore.put("ops.cancelShield.invokeAny.cancelSucceeded", cancelSucceeded);
                if (outcomeTimedOut) {
                    TraceStore.put("timeout.stage", "cancel_shield_invoke_any");
                }
            } catch (Throwable telemetryError) {
                traceTelemetrySkipped("invokeAny.outcome", telemetryError);
            }

            // Append an event (bounded by TraceStore clipper in renderers).
            try {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("tsMs", System.currentTimeMillis());
                ev.put("ownerHash", SafeRedactor.hashValue(owner));
                ev.put("ownerLength", owner == null ? 0 : owner.length());
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
                    String lastExClass = lastEx.getClass().getName();
                    ev.put("lastEx", "invoke_any_task_failed");
                    ev.put("lastExHash", SafeRedactor.hashValue(lastExClass));
                    ev.put("lastExLength", lastExClass.length());
                }
                TraceStore.append("ops.cancelShield.invokeAny.events", ev);
            } catch (Throwable telemetryError) {
                traceTelemetrySkipped("invokeAny.event", telemetryError);
            }
        }
    }

    private static String safeOwner(String value) {
        return SafeRedactor.traceLabelOrFallback(value, "executor");
    }

    private static void traceTelemetrySkipped(String stage, Throwable error) {
        try {
            TraceStore.inc("ops.cancelShield.telemetry.skipped.count");
            TraceStore.put("ops.cancelShield.telemetry.skipped.stage",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"));
            TraceStore.put("ops.cancelShield.telemetry.skipped.errorType", errorType(error));
        } catch (RuntimeException traceError) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[AWX][cancel-shield] telemetry trace skipped stage={0} errorType={1}",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                    errorType(traceError));
        }
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}

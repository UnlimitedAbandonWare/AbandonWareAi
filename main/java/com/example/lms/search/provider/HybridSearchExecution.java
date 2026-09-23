package com.example.lms.search.provider;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Owns one search's submitted tasks, not the executor, HTTP clients, or shared cache loaders. */
final class HybridSearchExecution implements AutoCloseable {
    private final TimeBudget budget;
    private final Map<String, Object> callerTrace;
    private final Map<String, Object> workerTrace = new WorkerTrace();
    private final List<OwnedAttempt<?>> attempts = new ArrayList<>();
    private boolean closed;
    private int running;
    private int finishedWorkers;
    private int completedWaiters;
    private int cancellationRequests;
    private int skippedBeforeStart;

    HybridSearchExecution(TimeBudget budget) {
        this.budget = budget;
        TraceStore.put("web.hybrid.execution.started", true);
        this.callerTrace = TraceStore.context();
        // Reactive callbacks started by synchronous facades must capture the same cutoff view.
        TraceStore.installContext(workerTrace);
    }

    <T> Future<T> submit(ExecutorService executor, Callable<T> work) {
        OwnedAttempt<T> attempt = new OwnedAttempt<>(work);
        synchronized (this) {
            attempts.add(attempt);
            if (closed || budget.expired() || Thread.currentThread().isInterrupted()) {
                skippedBeforeStart++;
                attempt.cancel(false);
                return attempt;
            }
        }
        try {
            // Retain the actual request-owned FutureTask. Executor wrappers remain unchanged.
            executor.execute(attempt);
            if (attempt.inlineExecutionRejected) {
                throw new RejectedExecutionException("hybrid_search_inline_execution_rejected");
            }
            return attempt;
        } catch (RuntimeException failure) {
            attempt.cancel(false);
            throw failure;
        } finally {
            attempt.executeCallActive = false;
        }
    }

    private <T> T run(Callable<T> work) throws Exception {
        synchronized (this) {
            // This is the start/terminal ordering point. Queue residence consumes the same budget.
            if (closed || budget.expired() || Thread.currentThread().isInterrupted()) {
                skippedBeforeStart++;
                return null;
            }
            running++;
        }
        TimeBudget previousBudget = TimeBudgetContext.get();
        Map<String, Object> previousTrace = TraceStore.context();
        try {
            TimeBudgetContext.set(budget);
            TraceStore.installContext(workerTrace);
            return work.call();
        } finally {
            TraceStore.installContext(previousTrace);
            if (previousBudget == null) TimeBudgetContext.clear();
            else TimeBudgetContext.set(previousBudget);
            synchronized (this) {
                running--;
                finishedWorkers++;
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (OwnedAttempt<?> attempt : attempts) {
            if (!attempt.isDone()) attempt.cancel(false);
        }
        // These are terminal snapshots. A logically cancelled waiter does not prove I/O stopped.
        callerTrace.put("web.hybrid.execution.submitted", attempts.size());
        callerTrace.put("web.hybrid.execution.skippedBeforeStart", skippedBeforeStart);
        callerTrace.put("web.hybrid.execution.waitersCompleted", completedWaiters);
        callerTrace.put("web.hybrid.execution.workersFinishedAtReturn", finishedWorkers);
        callerTrace.put("web.hybrid.execution.workersRunningAtReturn", running);
        callerTrace.put("web.hybrid.execution.cancellationRequests", cancellationRequests);
        callerTrace.put("web.hybrid.execution.lateTraceExcluded", true);
        TraceStore.installContext(callerTrace);
    }

    private final class OwnedAttempt<T> extends FutureTask<T> {
        private final Thread submittingThread = Thread.currentThread();
        private volatile boolean executeCallActive = true;
        private volatile boolean inlineExecutionRejected;

        private OwnedAttempt(Callable<T> work) { super(() -> HybridSearchExecution.this.run(work)); }

        @Override
        public void run() {
            if (isDone()) return;
            if (executeCallActive && Thread.currentThread() == submittingThread) {
                // CallerRunsPolicy must not start provider I/O outside the caller's timed wait.
                inlineExecutionRejected = true;
                synchronized (HybridSearchExecution.this) {
                    skippedBeforeStart++;
                }
                cancel(false);
                return;
            }
            super.run();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (HybridSearchExecution.this) {
                if (isDone()) return false;
                cancellationRequests++;
                // The task may be a waiter on shared cache work. Never interrupt that owner.
                return super.cancel(false);
            }
        }

        @Override
        protected void done() {
            synchronized (HybridSearchExecution.this) {
                completedWaiters++;
            }
        }
    }

    /** A lifetime-limited view of the existing trace map; no identifiers or memory are copied. */
    private final class WorkerTrace extends AbstractMap<String, Object> {
        private boolean writable(Object key) {
            return !closed && !"sessionId".equals(key) && !"sid".equals(key)
                    && !"ctx.memory".equals(key)
                    && !(key instanceof String s && s.startsWith("ctx.memory."));
        }

        private Object read(Object key) {
            Object value = callerTrace.get(key);
            if ("__trace.internal.keys".equals(key) && value instanceof Set<?> set) {
                return new InternalKeys(set);
            }
            return value;
        }

        @Override public Object get(Object key) {
            synchronized (HybridSearchExecution.this) { return read(key); }
        }
        @Override public Object put(String key, Object value) {
            synchronized (HybridSearchExecution.this) {
                return writable(key) ? callerTrace.put(key, value) : read(key);
            }
        }
        @Override public Object remove(Object key) {
            synchronized (HybridSearchExecution.this) {
                return writable(key) ? callerTrace.remove(key) : read(key);
            }
        }
        @Override public Object putIfAbsent(String key, Object value) {
            synchronized (HybridSearchExecution.this) {
                return writable(key) ? callerTrace.putIfAbsent(key, value) : read(key);
            }
        }
        @Override public Object compute(String key, BiFunction<? super String, ? super Object, ?> fn) {
            synchronized (HybridSearchExecution.this) {
                return writable(key) ? callerTrace.compute(key, fn) : read(key);
            }
        }
        @Override public Object computeIfAbsent(String key, Function<? super String, ?> fn) {
            synchronized (HybridSearchExecution.this) {
                return writable(key) ? callerTrace.computeIfAbsent(key, fn) : read(key);
            }
        }
        @Override public Set<Entry<String, Object>> entrySet() {
            return Collections.unmodifiableMap(callerTrace).entrySet();
        }
    }

    /** TraceStore updates this set separately from put/compute; keep its mutation under the same cutoff. */
    private final class InternalKeys extends AbstractSet<String> {
        private final Set<String> delegate;
        @SuppressWarnings("unchecked")
        private InternalKeys(Set<?> delegate) { this.delegate = (Set<String>) delegate; }
        @Override public Iterator<String> iterator() { return Collections.unmodifiableSet(delegate).iterator(); }
        @Override public int size() { return delegate.size(); }
        @Override public boolean add(String key) {
            synchronized (HybridSearchExecution.this) { return !closed && delegate.add(key); }
        }
        @Override public boolean remove(Object key) {
            synchronized (HybridSearchExecution.this) { return !closed && delegate.remove(key); }
        }
    }
}

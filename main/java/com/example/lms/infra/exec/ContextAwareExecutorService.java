package com.example.lms.infra.exec;

import ai.abandonware.nova.boot.exec.CancelShieldExecutorService;
import ai.abandonware.nova.boot.exec.CancelShieldFuture;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ExecutorService wrapper that propagates:
 * <ul>
 *   <li>MDC (sid/traceId/etc)</li>
 *   <li>GuardContext (ThreadLocal)</li>
 *   <li>TraceStore (request-scoped trace metadata)</li>
 * </ul>
 *
 * <p>Motivation:
 * <ul>
 *   <li>Soak/CLI/스케줄러 같은 "비-HTTP" 트리거에서도 provider override 및 trace 상관관계를 유지</li>
 *   <li>HybridWebSearchProvider/Orchestrator가 ExecutorService 스레드로 넘어가면서
 *       ThreadLocal(GuardContext) 및 MDC가 끊기는 문제를 방지</li>
 *   <li>Reactive/async 경계에서 TraceStore meta가 끊기는 문제를 방지</li>
 * </ul>
 */
public class ContextAwareExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    public ContextAwareExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        GuardContext guard = captureGuardOrDefault();
        Map<String, Object> traceCtx = TraceStore.context();
        // MERGE_HOOK:PROJ_AGENT::TRACE_STORE_PROPAGATION_IN_EXECUTOR_V1
        delegate.execute(wrap(command, mdc, guard, traceCtx));
    }


    /**
     * Explicit request-owned work: unlike submit(), cancellation may interrupt
     * this worker and removes its exact queue entry. Completion is waiter
     * completion only; it does not prove that an interrupted provider stopped.
     */
    public <T> Future<T> submitCancellable(Callable<T> work, Runnable completion) {
        final Thread submitter = Thread.currentThread();
        class OwnedTask extends java.util.concurrent.FutureTask<T> {
            volatile boolean submitting = true;
            volatile boolean inlineRejected;
            OwnedTask() { super(ContextPropagation.wrapCallable(work)); }
            @Override public void run() {
                if (submitting && Thread.currentThread() == submitter) {
                    inlineRejected = true;
                    reject(new java.util.concurrent.RejectedExecutionException("request_owned_inline_rejected"));
                    return;
                }
                super.run();
            }
            void reject(java.util.concurrent.RejectedExecutionException failure) { setException(failure); }
            @Override public boolean cancel(boolean interrupt) {
                boolean cancelled = super.cancel(interrupt);
                removeQueued();
                return cancelled;
            }
            void removeQueued() {
                if (delegate instanceof java.util.concurrent.ThreadPoolExecutor pool) pool.remove(this);
            }
            @Override protected void done() {
                if (completion != null) completion.run();
            }
        }
        OwnedTask task = new OwnedTask();
        try {
            if (delegate.isShutdown()) {
                throw new java.util.concurrent.RejectedExecutionException("executor_shutdown");
            }
            delegate.execute(task);
            if (task.inlineRejected) {
                throw new java.util.concurrent.RejectedExecutionException("request_owned_inline_rejected");
            }
            return task;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            task.reject(rejected);
            throw rejected;
        } finally {
            task.submitting = false;
            // A cancellation can win before execute actually enqueues the task.
            if (task.isCancelled()) task.removeQueued();
        }
    }

    @Override
    public Future<?> submit(Runnable task) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        GuardContext guard = captureGuardOrDefault();
        Map<String, Object> traceCtx = TraceStore.context();
        return shield(delegate.submit(wrap(task, mdc, guard, traceCtx)));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        GuardContext guard = captureGuardOrDefault();
        Map<String, Object> traceCtx = TraceStore.context();
        return shield(delegate.submit(wrap(task, mdc, guard, traceCtx), result));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        GuardContext guard = captureGuardOrDefault();
        Map<String, Object> traceCtx = TraceStore.context();
        return shield(delegate.submit(wrap(task, mdc, guard, traceCtx)));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        GuardContext guard = captureGuardOrDefault();
        Map<String, Object> traceCtx = TraceStore.context();
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> t : tasks) {
            wrapped.add(wrap(t, mdc, guard, traceCtx));
        }
        return new CancelShieldExecutorService(delegate, "contextAwareExecutor")
                .invokeAll(wrapped);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        GuardContext guard = captureGuardOrDefault();
        Map<String, Object> traceCtx = TraceStore.context();
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> t : tasks) {
            wrapped.add(wrap(t, mdc, guard, traceCtx));
        }
        return new CancelShieldExecutorService(delegate, "contextAwareExecutor")
                .invokeAll(wrapped, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        GuardContext guard = captureGuardOrDefault();
        Map<String, Object> traceCtx = TraceStore.context();
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> t : tasks) {
            wrapped.add(wrap(t, mdc, guard, traceCtx));
        }
        return new CancelShieldExecutorService(delegate, "contextAwareExecutor")
                .invokeAny(wrapped);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        GuardContext guard = captureGuardOrDefault();
        Map<String, Object> traceCtx = TraceStore.context();
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> t : tasks) {
            wrapped.add(wrap(t, mdc, guard, traceCtx));
        }
        return new CancelShieldExecutorService(delegate, "contextAwareExecutor")
                .invokeAny(wrapped, timeout, unit);
    }

    private static <T> Future<T> shield(Future<T> future) {
        if (future instanceof CancelShieldFuture<?>) {
            return future;
        }
        return new CancelShieldFuture<>(future, "contextAwareExecutor");
    }

    private GuardContext captureGuardOrDefault() {
        GuardContext guard = GuardContextHolder.get();
        return (guard != null) ? guard : GuardContext.defaultContext();
    }

    private Runnable wrap(Runnable task, Map<String, String> mdc, GuardContext guard, Map<String, Object> traceCtx) {
        return () -> {
            Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            GuardContext prevGuard = GuardContextHolder.get();
            Map<String, Object> prevTrace = TraceStore.context();
            try {
                applyMdc(mdc);
                applyGuard(guard);
                applyTrace(traceCtx);
                task.run();
            } finally {
                applyTrace(prevTrace);
                applyGuard(prevGuard);
                applyMdc(prevMdc);
            }
        };
    }

    private <T> Callable<T> wrap(Callable<T> task, Map<String, String> mdc, GuardContext guard,
            Map<String, Object> traceCtx) {
        return () -> {
            Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            GuardContext prevGuard = GuardContextHolder.get();
            Map<String, Object> prevTrace = TraceStore.context();
            try {
                applyMdc(mdc);
                applyGuard(guard);
                applyTrace(traceCtx);
                return task.call();
            } finally {
                applyTrace(prevTrace);
                applyGuard(prevGuard);
                applyMdc(prevMdc);
            }
        };
    }

    private void applyMdc(Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(mdc);
    }

    private void applyGuard(GuardContext guard) {
        if (guard == null) {
            GuardContextHolder.clear();
            return;
        }
        GuardContextHolder.set(guard);
    }

    // MERGE_HOOK:PROJ_AGENT::TRACE_STORE_APPLY_HELPER_V1
    private void applyTrace(Map<String, Object> traceCtx) {
        if (traceCtx == null) {
            TraceStore.clear();
            return;
        }
        TraceStore.installContext(traceCtx);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}

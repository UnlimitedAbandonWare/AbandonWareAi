package com.example.lms.infra.exec;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Utilities for propagating request-scoped context (MDC + GuardContextHolder + TraceStore)
 * across async boundaries.
 *
 * <p>Why this exists:</p>
 * <ul>
 *   <li>{@code ThreadLocal} values are not automatically propagated to pooled worker threads.</li>
 *   <li>Bounded pools (and Reactor schedulers) reuse threads; without {@code finally} cleanup,
 *       ThreadLocal state can leak across unrelated requests.</li>
 * </ul>
 *
 * <p>These wrappers capture the current thread's MDC map, {@link GuardContext} and
 * {@link TraceStore} context and restore the previous state after execution.</p>
 */
public final class ContextPropagation {

    private ContextPropagation() {
    }

    /** Wrap a runnable with MDC + GuardContext + TraceStore propagation (capture now, apply on run). */
    public static Runnable wrap(Runnable task) {
        if (task == null) {
            return () -> {
            };
        }
        final Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        GuardContext capturedGuard = safeGetGuard();
        if (capturedGuard == null) {
            // Provide a non-null context to avoid null-sensitive downstream code.
            capturedGuard = GuardContext.defaultContext();
        }
        final GuardContext guardRef = capturedGuard;

        final Map<String, Object> capturedTrace = TraceStore.context();

        return () -> {
            final Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            final GuardContext prevGuard = safeGetGuard();
            final Map<String, Object> prevTrace = TraceStore.context();
            try {
                applyMdc(capturedMdc);
                safeApplyGuard(guardRef);
                applyTrace(capturedTrace);
                task.run();
            } finally {
                applyTrace(prevTrace);
                safeApplyGuard(prevGuard);
                applyMdc(prevMdc);
            }
        };
    }

    /** Wrap a supplier with MDC + GuardContext + TraceStore propagation (capture now, apply on get). */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> supplier) {
        if (supplier == null) {
            return () -> null;
        }
        final Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        GuardContext capturedGuard = safeGetGuard();
        if (capturedGuard == null) {
            capturedGuard = GuardContext.defaultContext();
        }
        final GuardContext guardRef = capturedGuard;

        final Map<String, Object> capturedTrace = TraceStore.context();

        return () -> {
            final Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            final GuardContext prevGuard = safeGetGuard();
            final Map<String, Object> prevTrace = TraceStore.context();
            try {
                applyMdc(capturedMdc);
                safeApplyGuard(guardRef);
                applyTrace(capturedTrace);
                return supplier.get();
            } finally {
                applyTrace(prevTrace);
                safeApplyGuard(prevGuard);
                applyMdc(prevMdc);
            }
        };
    }

    /** Wrap a callable with MDC + GuardContext + TraceStore propagation (capture now, apply on call). */
    public static <T> Callable<T> wrapCallable(Callable<T> callable) {
        if (callable == null) {
            return () -> null;
        }
        final Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        GuardContext capturedGuard = safeGetGuard();
        if (capturedGuard == null) {
            capturedGuard = GuardContext.defaultContext();
        }
        final GuardContext guardRef = capturedGuard;

        final Map<String, Object> capturedTrace = TraceStore.context();

        return () -> {
            final Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            final GuardContext prevGuard = safeGetGuard();
            final Map<String, Object> prevTrace = TraceStore.context();
            try {
                applyMdc(capturedMdc);
                safeApplyGuard(guardRef);
                applyTrace(capturedTrace);
                return callable.call();
            } finally {
                applyTrace(prevTrace);
                safeApplyGuard(prevGuard);
                applyMdc(prevMdc);
            }
        };
    }

    private static void applyMdc(Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdc);
        }
    }

    private static GuardContext safeGetGuard() {
        try {
            return GuardContextHolder.get();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static void safeApplyGuard(GuardContext guard) {
        try {
            if (guard == null) {
                GuardContextHolder.clear();
            } else {
                GuardContextHolder.set(guard);
            }
        } catch (Throwable ignore) {
            // fail-soft: guard context propagation is optional
        }
    }

    private static void applyTrace(Map<String, Object> trace) {
        if (trace == null) {
            TraceStore.clear();
        } else {
            TraceStore.installContext(trace);
        }
    }
}

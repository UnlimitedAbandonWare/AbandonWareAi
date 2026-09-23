package com.example.lms.infra.exec;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.MDC;

import java.io.IOException;
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

    private static final System.Logger LOG = System.getLogger(ContextPropagation.class.getName());

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
        final TimeBudget capturedBudget = TimeBudgetContext.get();

        return () -> {
            final Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            final GuardContext prevGuard = safeGetGuard();
            final Map<String, Object> prevTrace = TraceStore.context();
            final TimeBudget prevBudget = TimeBudgetContext.get();
            try {
                applyMdc(capturedMdc);
                safeApplyGuard(guardRef);
                applyTrace(capturedTrace);
                applyTimeBudget(capturedBudget);
                task.run();
            } catch (Exception failure) {
                if (isServletAsyncDisconnectRace(failure)) {
                    traceSuppressed("contextPropagation.servletAsyncDisconnect", failure);
                    LOG.log(System.Logger.Level.DEBUG,
                            "Servlet async client disconnect race suppressed errorType=" + errorType(failure));
                    return;
                }
                if (failure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                sneakyThrow(failure);
            } finally {
                applyTimeBudget(prevBudget);
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
        final TimeBudget capturedBudget = TimeBudgetContext.get();

        return () -> {
            final Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            final GuardContext prevGuard = safeGetGuard();
            final Map<String, Object> prevTrace = TraceStore.context();
            final TimeBudget prevBudget = TimeBudgetContext.get();
            try {
                applyMdc(capturedMdc);
                safeApplyGuard(guardRef);
                applyTrace(capturedTrace);
                applyTimeBudget(capturedBudget);
                return supplier.get();
            } finally {
                applyTimeBudget(prevBudget);
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
        final TimeBudget capturedBudget = TimeBudgetContext.get();

        return () -> {
            final Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            final GuardContext prevGuard = safeGetGuard();
            final Map<String, Object> prevTrace = TraceStore.context();
            final TimeBudget prevBudget = TimeBudgetContext.get();
            try {
                applyMdc(capturedMdc);
                safeApplyGuard(guardRef);
                applyTrace(capturedTrace);
                applyTimeBudget(capturedBudget);
                return callable.call();
            } finally {
                applyTimeBudget(prevBudget);
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
            traceSuppressed("contextPropagation.guardGet", ignore);
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
            traceSuppressed("contextPropagation.guardApply", ignore);
        }
    }

    private static void applyTrace(Map<String, Object> trace) {
        if (trace == null) {
            TraceStore.clear();
        } else {
            TraceStore.installContext(trace);
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("context.propagation.suppressed." + safeStage, true);
        TraceStore.put("context.propagation.suppressed." + safeStage + ".errorType",
                errorType(failure));
    }

    private static void applyTimeBudget(TimeBudget budget) {
        if (budget == null) {
            TimeBudgetContext.clear();
        } else {
            TimeBudgetContext.set(budget);
        }
    }

    private static boolean isServletAsyncDisconnectRace(Throwable failure) {
        if (failure instanceof IllegalStateException) {
            String message = failure.getMessage();
            return message != null
                && message.contains("A non-container (application) thread attempted to use the AsyncContext")
                && message.contains("after an error had occurred");
        }
        return failure instanceof IOException
                && Thread.currentThread().getName().startsWith("mvc-async-")
                && hasStackFrame(
                failure,
                "org.springframework.web.servlet.mvc.method.annotation.ReactiveTypeHandler$SseEmitterSubscriber",
                "send");
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static boolean hasStackFrame(Throwable failure, String className, String methodName) {
        if (failure == null) {
            return false;
        }
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (className.equals(frame.getClassName()) && methodName.equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
    }
}

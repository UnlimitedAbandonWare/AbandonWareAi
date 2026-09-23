package ai.abandonware.nova.boot.exec;

import com.example.lms.cfvm.CfvmFailureRecoveryHandler;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A {@link Future} wrapper that prevents {@code cancel(true)} from interrupting the running thread.
 *
 * <p>
 * Why: some legacy call-sites aggressively invoke {@code Future.cancel(true)} on timeout.
 * In a pooled executor this can "poison" the worker thread with an interrupt flag and
 * cascade into unrelated tasks (especially reactive/WebClient chains) being cancelled.
 *
 * <p>
 * This wrapper converts {@code cancel(true)} into {@code cancel(false)} while preserving the
 * caller-visible cancellation semantics (the future becomes cancelled and its result is ignored)
 * without delivering an interrupt to the worker.
 */
public final class CancelShieldFuture<T> implements Future<T> {

    private static final Logger log = LoggerFactory.getLogger(CancelShieldFuture.class);

    private static final AtomicLong IDS = new AtomicLong(0L);

    private final Future<T> delegate;
    private final String owner;
    private final Supplier<DebugEventStore> debugEventStoreSupplier;
    private final Supplier<CfvmFailureRecoveryHandler> recoveryHandlerSupplier;

    /** Best-effort "soft cancel" marker when delegate cancellation throws. */
    private volatile boolean softCancelled;

    private final String id;

    public CancelShieldFuture(Future<T> delegate, String owner) {
        this(delegate, owner, null, null);
    }

    public CancelShieldFuture(Future<T> delegate,
                              String owner,
                              Supplier<DebugEventStore> debugEventStoreSupplier,
                              Supplier<CfvmFailureRecoveryHandler> recoveryHandlerSupplier) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.owner = safeOwner(owner);
        this.debugEventStoreSupplier = debugEventStoreSupplier;
        this.recoveryHandlerSupplier = recoveryHandlerSupplier;
        this.id = this.owner + "-" + IDS.incrementAndGet();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (softCancelled) {
            return true;
        }

        // We *never* call delegate.cancel(true) from this wrapper.
        // The whole point is to prevent interrupt poisoning.
        final boolean requestedInterrupt = mayInterruptIfRunning;

        if (requestedInterrupt) {
            try {
                TraceStore.inc("ops.cancelShield.cancelTrue.count");
            } catch (Throwable traceError) {
                traceTelemetrySkipped("cancel_true_count", traceError);
            }
        } else {
            try {
                TraceStore.inc("ops.cancelShield.cancelFalse.count");
            } catch (Throwable traceError) {
                traceTelemetrySkipped("cancel_false_count", traceError);
            }
        }

        boolean cancelled;
        Throwable err = null;
        try {
            cancelled = delegate.cancel(false);
            if (requestedInterrupt && !cancelled && !isDelegateDone()) {
                softCancelled = true;
                cancelled = true;
            }
        } catch (Throwable t) {
            // Some Future implementations (rare) can throw here.
            // We fail-soft by marking this wrapper as "soft cancelled" so upstream
            // won't block forever, but we DO NOT interrupt the worker.
            cancelled = true;
            err = t;
            softCancelled = true;
            traceTelemetrySkipped("delegate_cancel", t);
        }

        recordCancel(requestedInterrupt, cancelled, err);

        return cancelled;
    }

    private boolean isDelegateDone() {
        try {
            return delegate.isDone();
        } catch (Throwable stateError) {
            traceTelemetrySkipped("delegate_done_probe", stateError);
            return false;
        }
    }

    @Override
    public boolean isCancelled() {
        return softCancelled || delegate.isCancelled();
    }

    @Override
    public boolean isDone() {
        return softCancelled || delegate.isDone();
    }

    @Override
    public T get() throws ExecutionException, InterruptedException {
        if (softCancelled) {
            throw new CancellationException("cancelled (soft)" + LogCorrelation.suffix());
        }
        return delegate.get();
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (softCancelled) {
            throw new CancellationException("cancelled (soft)" + LogCorrelation.suffix());
        }
        return delegate.get(timeout, unit);
    }

    private void recordCancel(boolean requestedInterrupt, boolean cancelled, Throwable err) {
        try {
            String mode = requestedInterrupt ? "cancel(true)->cancel(false)" : "cancel(false)";
            String outcome;
            if (err != null) {
                outcome = "soft_cancel";
                TraceStore.inc("ops.cancelShield.softCancelled.count");
            } else if (requestedInterrupt) {
                outcome = cancelled ? "downgraded_ok" : "downgraded_noop";
                if (cancelled) {
                    TraceStore.inc("ops.cancelShield.downgraded.count");
                } else {
                    TraceStore.inc("ops.cancelShield.downgraded.noop.count");
                }
            } else {
                outcome = cancelled ? "cancel_ok" : "cancel_noop";
            }

            TraceStore.put("ops.cancelShield.last.ownerHash", SafeRedactor.hashValue(owner));
            TraceStore.put("ops.cancelShield.last.ownerLength", owner == null ? 0 : owner.length());
            TraceStore.put("ops.cancelShield.last.idHash", SafeRedactor.hashValue(id));
            TraceStore.put("ops.cancelShield.last.idLength", id == null ? 0 : id.length());
            TraceStore.put("ops.cancelShield.last.mode", mode);
            TraceStore.put("ops.cancelShield.last.outcome", outcome);
            TraceStore.put("ops.cancelShield.last.caller", callerHint());
            if (requestedInterrupt) {
                TraceStore.put("cancel.suppressed", true);
                TraceStore.put("cancel.suppressed.reason", "cancel_true_downgraded");
                TraceStore.put("cancel.suppressed.errorType", "cancel_true_downgraded");
                recordCfvmRecovery(cancelled);
            }
            if (err != null) {
                TraceStore.put("ops.cancelShield.last.err", "cancel_shield_cancel_failed");
            }

            // Optional per-request event stream (cap to avoid unbounded growth).
            long n = TraceStore.inc("ops.cancelShield.events.count");
            if (n <= 32) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("seq", TraceStore.nextSequence("cancelShield"));
                ev.put("tNs", System.nanoTime());
                ev.put("ownerHash", SafeRedactor.hashValue(owner));
                ev.put("ownerLength", owner == null ? 0 : owner.length());
                ev.put("idHash", SafeRedactor.hashValue(id));
                ev.put("idLength", id == null ? 0 : id.length());
                ev.put("mode", mode);
                ev.put("outcome", outcome);
                ev.put("caller", callerHint());
                if (err != null) {
                    ev.put("err", "cancel_shield_cancel_failed");
                }
                TraceStore.append("ops.cancelShield.events", ev);
            }
        } catch (Throwable recordError) {
            traceTelemetrySkipped("record_cancel", recordError);
        }

        if (err != null) {
            // keep log at debug only (the system prefers trace-driven debugging)
            log.debug("[CancelShield] delegate.cancel(false) threw; soft-cancelled ownerHash={} idHash={} errorHash={} errorLength={}{}",
                    SafeRedactor.hashValue(owner), SafeRedactor.hashValue(id),
                    SafeRedactor.hashValue(messageOf(err)), messageLength(err), LogCorrelation.suffix());
        }
    }

    private void recordCfvmRecovery(boolean cancelled) {
        try {
            if (debugEventStoreSupplier != null) {
                TraceStore.put("cfvm.failureRecovery.debugEventSupplier.present", true);
            }
            CfvmFailureRecoveryHandler handler = recoveryHandlerSupplier == null
                    ? null
                    : recoveryHandlerSupplier.get();
            if (handler == null) {
                TraceStore.put("cfvm.failureRecovery.considered", true);
                TraceStore.put("cfvm.failureRecovery.handlerAvailable", false);
                return;
            }
            TraceStore.put("cfvm.failureRecovery.handlerAvailable", true);
            handler.observeCancelShieldDowngrade(owner, true, cancelled);
        } catch (Throwable recoveryError) {
            try {
                TraceStore.put("cfvm.failureRecovery.suppressed", true);
                TraceStore.put("cfvm.failureRecovery.suppressed.stage", "cancelShieldFuture.recordCfvmRecovery");
                TraceStore.put("cfvm.failureRecovery.suppressed.errorType", errorType(recoveryError));
            } catch (RuntimeException traceError) {
                traceTelemetrySkipped("cfvm_recovery_trace", traceError);
            }
        }
    }

    private static String messageOf(Throwable error) {
        return error == null ? "" : String.valueOf(error.getMessage());
    }

    private static int messageLength(Throwable error) {
        return messageOf(error).length();
    }

    private static String callerHint() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            if (st == null || st.length == 0) {
                return "";
            }
            // 0:getStackTrace 1:callerHint 2:recordCancel 3:cancel ... -> skip until outside nova/exec
            for (int i = 0; i < st.length; i++) {
                StackTraceElement e = st[i];
                if (e == null) continue;
                String cn = e.getClassName();
                if (cn == null) continue;
                if (cn.startsWith("ai.abandonware.nova.boot.exec.")) continue;
                if (cn.startsWith("java.util.concurrent.")) continue;
                if (cn.startsWith("java.lang.")) continue;
                return cn + "#" + e.getMethodName() + ":" + e.getLineNumber();
            }
        } catch (Throwable callerError) {
            traceTelemetrySkipped("caller_hint", callerError);
        }
        return "";
    }

    private static void traceTelemetrySkipped(String stage, Throwable error) {
        log.debug("[CancelShield] telemetry skipped stage={} errorType={}{}",
                stage, errorType(error), LogCorrelation.suffix());
    }

    private static String safeOwner(String value) {
        return SafeRedactor.traceLabelOrFallback(value, "executor");
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}

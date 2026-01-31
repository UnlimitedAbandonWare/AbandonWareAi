package ai.abandonware.nova.boot.exec;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.LogCorrelation;
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

    /** Best-effort "soft cancel" marker when delegate cancellation throws. */
    private volatile boolean softCancelled;

    private final String id;

    public CancelShieldFuture(Future<T> delegate, String owner) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.owner = (owner == null || owner.isBlank()) ? "executor" : owner;
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
            } catch (Throwable ignore) {
            }
        } else {
            try {
                TraceStore.inc("ops.cancelShield.cancelFalse.count");
            } catch (Throwable ignore) {
            }
        }

        boolean cancelled;
        Throwable err = null;
        try {
            cancelled = delegate.cancel(false);
        } catch (Throwable t) {
            // Some Future implementations (rare) can throw here.
            // We fail-soft by marking this wrapper as "soft cancelled" so upstream
            // won't block forever, but we DO NOT interrupt the worker.
            cancelled = true;
            err = t;
            softCancelled = true;
        }

        recordCancel(requestedInterrupt, cancelled, err);

        return cancelled;
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

            TraceStore.put("ops.cancelShield.last.owner", owner);
            TraceStore.put("ops.cancelShield.last.id", id);
            TraceStore.put("ops.cancelShield.last.mode", mode);
            TraceStore.put("ops.cancelShield.last.outcome", outcome);
            TraceStore.put("ops.cancelShield.last.caller", callerHint());
            if (err != null) {
                TraceStore.put("ops.cancelShield.last.err", err.getClass().getSimpleName());
            }

            // Optional per-request event stream (cap to avoid unbounded growth).
            long n = TraceStore.inc("ops.cancelShield.events.count");
            if (n <= 32) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("seq", TraceStore.nextSequence("cancelShield"));
                ev.put("tNs", System.nanoTime());
                ev.put("owner", owner);
                ev.put("id", id);
                ev.put("mode", mode);
                ev.put("outcome", outcome);
                ev.put("caller", callerHint());
                if (err != null) {
                    ev.put("err", err.getClass().getSimpleName());
                }
                TraceStore.append("ops.cancelShield.events", ev);
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        if (err != null) {
            // keep log at debug only (the system prefers trace-driven debugging)
            log.debug("[CancelShield] delegate.cancel(false) threw; soft-cancelled (owner={}, id={}): {}{}",
                    owner, id, err.toString(), LogCorrelation.suffix());
        }
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
        } catch (Throwable ignore) {
        }
        return "";
    }
}

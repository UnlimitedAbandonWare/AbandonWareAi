package ai.abandonware.nova.boot.exec;

import com.example.lms.infra.exec.ContextPropagation;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * ScheduledExecutorService wrapper with MDC/GuardContext/TraceStore propagation.
 */
public class ContextPropagatingScheduledExecutorService extends ContextPropagatingExecutorService implements ScheduledExecutorService {

    private final ScheduledExecutorService delegate;

    public ContextPropagatingScheduledExecutorService(ScheduledExecutorService delegate) {
        super(delegate);
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return delegate.schedule(ContextPropagation.wrap(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return delegate.schedule(ContextPropagation.wrapCallable(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return delegate.scheduleAtFixedRate(ContextPropagation.wrap(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return delegate.scheduleWithFixedDelay(ContextPropagation.wrap(command), initialDelay, delay, unit);
    }

    @Override
    public String toString() {
        return "ContextPropagatingScheduledExecutorService{" + delegate + "}";
    }
}

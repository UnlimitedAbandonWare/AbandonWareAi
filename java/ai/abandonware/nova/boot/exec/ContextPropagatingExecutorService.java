package ai.abandonware.nova.boot.exec;

import com.example.lms.infra.exec.ContextPropagation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Wrapper for {@link ExecutorService} that propagates request-scoped context
 * (MDC + GuardContext + TraceStore) across thread pool boundaries.
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li>Fail-soft: if ContextPropagation fails, still execute.</li>
 *   <li>Low surprise: delegate lifecycle methods unchanged.</li>
 * </ul>
 */
public class ContextPropagatingExecutorService implements ExecutorService {

    protected final ExecutorService delegate;

    public ContextPropagatingExecutorService(ExecutorService delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    protected Runnable wrap(Runnable r) {
        try {
            return ContextPropagation.wrap(r);
        } catch (Throwable ignore) {
            return r;
        }
    }

    protected <T> Callable<T> wrapCallable(Callable<T> c) {
        try {
            return ContextPropagation.wrapCallable(c);
        } catch (Throwable ignore) {
            return c;
        }
    }

    protected <T> Collection<? extends Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return tasks;
        }
        List<Callable<T>> out = new ArrayList<>(tasks.size());
        for (Callable<T> t : tasks) {
            out.add(wrapCallable(t));
        }
        return out;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(wrap(command));
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

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrapCallable(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(wrap(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, java.util.concurrent.ExecutionException {
        return delegate.invokeAny(wrapAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        return delegate.invokeAny(wrapAll(tasks), timeout, unit);
    }

    public ExecutorService unwrap() {
        return delegate;
    }

    @Override
    public String toString() {
        return "ContextPropagatingExecutorService{" + delegate + "}";
    }
}

package com.example.lms.service.rag.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SafeRetrieverRunner wraps potentially long-running retrieval operations and provides
 * a timeout with fail-soft behaviour.  Handlers can delegate work through this
 * runner to ensure responsiveness without risking thread starvation.
 */
@Component
public class SafeRetrieverRunner {

    private static final Logger log = LoggerFactory.getLogger(SafeRetrieverRunner.class);
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(1);

    /**
     * Runs a task with the given timeout and returns its result.  When the task fails
     * or exceeds the timeout, the provided fallback value is returned and a warning is logged.
     *
     * @param task the callable to run
     * @param timeout how long to wait before cancelling the task
     * @param fallback the value to return if the task fails or times out
     * @param <T> result type
     * @return the result or the fallback
     */
    public <T> T run(Callable<T> task, Duration timeout, T fallback) {
        Future<T> f = Executors.newSingleThreadExecutor().submit(task);
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("[SafeRetrieverRunner] fail-soft: {}", e.toString());
            return fallback;
        } finally {
            f.cancel(true);
        }
    }
}

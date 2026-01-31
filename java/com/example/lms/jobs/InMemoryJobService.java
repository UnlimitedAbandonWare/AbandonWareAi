package com.example.lms.jobs;

import com.example.lms.infra.exec.ContextAwareExecutorService;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in-memory job service used for local development and tests.
 * Not production ready: no persistence and process-local only.
 */
public class InMemoryJobService implements JobService {
    private final ConcurrentMap<String, String> status = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> payloads = new ConcurrentHashMap<>();
    // ThreadLocal(MDC/GuardContext) propagation is required because jobs may be
    // enqueued from a web request thread but executed on pooled workers.
    private final ExecutorService exec = new ContextAwareExecutorService(
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "job-exec-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            }));
    private final AtomicLong seq = new AtomicLong();

    @Override
    public String enqueue(String payload) {
        String id = Long.toHexString(System.currentTimeMillis()) + "-" + seq.incrementAndGet();
        status.put(id, "PENDING");
        payloads.put(id, payload);
        return id;
    }

    @Override
    public <T> void executeAsync(String jobId, Supplier<T> work, Consumer<T> onSuccess) {
        status.put(jobId, "RUNNING");
        exec.submit(() -> {
            try {
                T result = work.get();
                status.put(jobId, "SUCCEEDED");
                if (onSuccess != null) onSuccess.accept(result);
            } catch (Throwable t) {
                status.put(jobId, "FAILED");
            }
        });
    }

    @Override
    public String status(String jobId) {
        return status.getOrDefault(jobId, "NOT_FOUND");
    }
}
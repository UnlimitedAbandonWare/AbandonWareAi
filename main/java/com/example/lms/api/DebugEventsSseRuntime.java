package com.example.lms.api;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the bounded worker lifecycle for the admin debug-events SSE endpoint. */
@Component
public final class DebugEventsSseRuntime {

    private static final int DEFAULT_MAX_CLIENTS = 8;
    private static final int MAX_CLIENTS_LIMIT = 64;
    private static final long KEEP_ALIVE_SECONDS = 30L;

    private final ThreadPoolExecutor executor;
    private final AtomicLong executionAttempts = new AtomicLong();

    @Autowired
    public DebugEventsSseRuntime(
            @Value("${lms.debug.events.sse.max-clients:8}") int configuredMaxClients) {
        this(configuredMaxClients, daemonThreadFactory());
    }

    DebugEventsSseRuntime(int configuredMaxClients, ThreadFactory threadFactory) {
        int maxClients = normalizeMaxClients(configuredMaxClients);
        this.executor = new ThreadPoolExecutor(
                0,
                maxClients,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Objects.requireNonNull(threadFactory, "threadFactory"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    void execute(Runnable task) {
        executionAttempts.incrementAndGet();
        executor.execute(Objects.requireNonNull(task, "task"));
    }

    long executionAttempts() {
        return executionAttempts.get();
    }

    int activeCount() {
        return executor.getActiveCount();
    }

    int queueSize() {
        return executor.getQueue().size();
    }

    long completedTaskCount() {
        return executor.getCompletedTaskCount();
    }

    boolean isShutdown() {
        return executor.isShutdown();
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static int normalizeMaxClients(int configuredMaxClients) {
        int positive = configuredMaxClients > 0 ? configuredMaxClients : DEFAULT_MAX_CLIENTS;
        return Math.min(positive, MAX_CLIENTS_LIMIT);
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "debug-events-sse-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

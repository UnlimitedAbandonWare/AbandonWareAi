package com.abandonware.ai.zsystem;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Z-System: time budgets, cancellation, semaphore guard for expensive ops, single-flight cache, final sigmoid gate. */
public class ZSystem {
    private static final Logger log = Logger.getLogger(ZSystem.class.getName());
    private static final AtomicInteger budgetThreadIds = new AtomicInteger();

    private final Semaphore rerankerSemaphore = new Semaphore(4); // configurable
    private final Map<String, CompletableFuture<Object>> singleFlight = new ConcurrentHashMap<>();

    public <T> T withBudget(long millis, Supplier<T> work, Supplier<T> fallback) {
        ExecutorService ex = Executors.newSingleThreadExecutor(ZSystem::newBudgetThread);
        Future<T> f = ex.submit(work::get);
        try {
            return f.get(millis, TimeUnit.MILLISECONDS);
        } catch(Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logFailSoft("withBudget", e);
            f.cancel(false);
            return fallback.get();
        } finally {
            ex.shutdown();
        }
    }

    public <T> T guardedRerank(Supplier<T> work, Supplier<T> fastPath) {
        if (rerankerSemaphore.tryAcquire()) {
            try { return work.get(); }
            finally { rerankerSemaphore.release(); }
        } else {
            return fastPath.get();
        }
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> singleFlight(String key, Supplier<T> supplier) {
        CompletableFuture<Object> existing = singleFlight.putIfAbsent(key, new CompletableFuture<>());
        if (existing != null) return (CompletableFuture<T>) existing;
        CompletableFuture<Object> created = singleFlight.get(key);
        CompletableFuture.runAsync(() -> {
            try {
                T val = supplier.get();
                created.complete(val);
            } catch(Exception e) {
                logFailSoft("singleFlight", e);
                created.completeExceptionally(e);
            } finally {
                singleFlight.remove(key);
            }
        });
        return (CompletableFuture<T>) created;
    }

    public double finalSigmoidGate(double x, double k, double x0) {
        return 1.0 / (1.0 + Math.exp(-k*(x - x0)));
    }

    private static Thread newBudgetThread(Runnable task) {
        Thread thread = new Thread(task, "zsystem-budget-" + budgetThreadIds.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    private static void logFailSoft(String stage, Exception e) {
        if (log.isLoggable(Level.FINE)) {
            String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
            log.fine("[AWX][extremez][zsystem] failSoft stage=" + stage + " errorType=" + errorType);
        }
    }
}

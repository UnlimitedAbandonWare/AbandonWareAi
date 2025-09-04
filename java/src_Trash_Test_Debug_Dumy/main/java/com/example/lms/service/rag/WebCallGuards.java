package com.example.lms.service.rag;
import java.util.concurrent.*;
import java.time.Duration;
import java.util.function.Supplier;

public class WebCallGuards {
    public static <T> T timeboxed(Supplier<T> task, Duration timeout, Supplier<T> fallback) {
        ExecutorService es = Executors.newCachedThreadPool();
        Future<T> f = es.submit(task::get);
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return fallback.get();
        } finally { es.shutdownNow(); }
    }
    public static <T> T hedged(Supplier<T> primary, Supplier<T> backup, long hedgeDelayMillis, Duration timeout, Supplier<T> fallback) {
        ExecutorService es = Executors.newCachedThreadPool();
        Future<T> f1 = es.submit(primary::get);
        try { Thread.sleep(hedgeDelayMillis); } catch (InterruptedException ignore) {}
        Future<T> f2 = es.submit(backup::get);
        try {
            return CompletableFuture.anyOf(CompletableFuture.supplyAsync(() -> {
                try { return f1.get(timeout.toMillis(), TimeUnit.MILLISECONDS); } catch(Exception e){ return null; }
            }), CompletableFuture.supplyAsync(() -> {
                try { return f2.get(timeout.toMillis(), TimeUnit.MILLISECONDS); } catch(Exception e){ return null; }
            })).thenApply(o -> o!=null ? (T)o : fallback.get()).get();
        } catch (Exception e) {
            return fallback.get();
        } finally { f1.cancel(true); f2.cancel(true); es.shutdownNow(); }
    }
}

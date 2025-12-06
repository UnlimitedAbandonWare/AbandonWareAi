// src/main/java/service/rag/cache/SingleFlightExecutor.java
package service.rag.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
public class SingleFlightExecutor<T> {

    private static final class InFlight<V> {
        final CompletableFuture<V> future = new CompletableFuture<>();
    }

    private final ConcurrentHashMap<String, InFlight<T>> flights = new ConcurrentHashMap<>();

    @Value("${features.singleflight.wait-ms:1200}")
    private int defaultWaitMs;

    public T compute(String key, Supplier<T> supplier) {
        return compute(key, supplier, defaultWaitMs);
    }

    public T compute(String key, Supplier<T> supplier, int waitMs) {
        InFlight<T> created = new InFlight<>();
        InFlight<T> reg = flights.putIfAbsent(key, created);
        InFlight<T> slot = (reg == null) ? created : reg;

        // 리더(첫 진입)만 실제 계산
        if (slot == created) {
            try {
                T val = supplier.get();
                slot.future.complete(val);
                return val;
            } catch (Throwable t) {
                slot.future.completeExceptionally(t);
                throw t;
            } finally {
                flights.remove(key, slot);
            }
        }

        // 팔로워는 대기(타임아웃 후 빈 결과/예외 전파는 호출부 정책)
        try {
            return slot.future.get(Math.max(0, waitMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // 타임아웃 시 즉시 포기(호출부에서 폴백)
            throw new CompletionException(te);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ie);
        } catch (ExecutionException ee) {
            throw new CompletionException(ee.getCause());
        }
    }
}
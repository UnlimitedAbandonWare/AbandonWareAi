package com.abandonware.ai.infra.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Single-flight deduplication to prevent cache stampede. */
@Component
public class SingleFlight {
  private final ConcurrentHashMap<String, CompletableFuture<?>> inflight = new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  public <T> T run(String key, Supplier<T> supplier) {
    CompletableFuture<T> cf = (CompletableFuture<T>) inflight.computeIfAbsent(key, k ->
      CompletableFuture.supplyAsync(supplier).whenComplete((r,e) -> inflight.remove(k))
    );
    try { return cf.get(); } catch (Exception e) { inflight.remove(key); throw new RuntimeException(e); }
  }
}
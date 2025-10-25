package com.example.lms.service.infra.cache;
import java.util.concurrent.*; 
import java.util.*; 
public final class SingleFlightExecutor {
  private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight=new ConcurrentHashMap<>();
  @SuppressWarnings("unchecked") 
  public <T> CompletableFuture<T> run(String key, Callable<T> task){
    return (CompletableFuture<T>) inflight.computeIfAbsent(key, k -> {
      CompletableFuture<T> f=new CompletableFuture<>();
      CompletableFuture.runAsync(() -> { 
        try{ f.complete(task.call()); } 
        catch(Exception e){ f.completeExceptionally(e);} 
        finally{ inflight.remove(k);} 
      });
      return (CompletableFuture<Object>)(CompletableFuture<?>)f; 
    });
  }
}

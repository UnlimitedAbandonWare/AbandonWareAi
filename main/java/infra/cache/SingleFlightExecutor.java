package infra.cache;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
public class SingleFlightExecutor<T> {
  private final ConcurrentHashMap<String, CompletableFuture<T>> inFlight = new ConcurrentHashMap<>();
  public CompletableFuture<T> run(String key, Callable<T> task){
    return inFlight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
      try { return task.call(); } catch (Exception e){ throw new RuntimeException(e); }
    })).whenComplete((v,e)-> inFlight.remove(key));
  }
}
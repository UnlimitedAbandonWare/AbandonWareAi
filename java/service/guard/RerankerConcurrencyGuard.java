package service.guard;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

public class RerankerConcurrencyGuard {
  private final Semaphore sem;
  public RerankerConcurrencyGuard(int max){ this.sem=new Semaphore(max); }

  public <T> T withPermit(Callable<T> c, long timeoutMs, Callable<T> fallback) {
    boolean ok=false;
    try {
      ok = sem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
      if (!ok) return fallback.call();
      return c.call();
    } catch(Exception e){
      try { return fallback.call(); }
      catch(Exception ee){ throw new RuntimeException(ee); }
    } finally {
      if (ok) sem.release();
    }
  }
}
package com.example.lms.service.rerank;
import java.util.List; 
import java.util.concurrent.Semaphore; 
import com.example.lms.service.runtime.TimeBudget;
public final class OnnxRerankerGuard {
  private final Semaphore limit;
  public OnnxRerankerGuard(int max){ this.limit=new Semaphore(Math.max(1, max)); }
  public <T> List<T> maybeRerank(List<T> candidates, TimeBudget budget, RerankFn<T> fn){
    if(candidates==null) return null;
    if(budget==null || budget.expired() || !limit.tryAcquire()) return candidates;
    try{
      long r=budget.remainingMillis(); if(r<300) return candidates;
      return fn.apply(candidates, r);
    } finally { limit.release(); }
  }
  @FunctionalInterface public interface RerankFn<T>{ List<T> apply(List<T> items, long millis); }
}

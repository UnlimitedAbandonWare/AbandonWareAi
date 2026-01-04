package com.abandonware.ai.service.onnx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class OnnxSemaphoreGuard {
  private final Semaphore sem;

  public OnnxSemaphoreGuard(@Value("${onnx.max-concurrency:4}") int maxConc) {
    this.sem = new Semaphore(Math.max(1, maxConc));
  }

  public <T> Optional<T> withPermit(Supplier<T> body, long timeoutMs) {
    boolean acquired = false;
    try {
      acquired = sem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
      if (acquired) return Optional.ofNullable(body.get());
      return Optional.empty();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      if (acquired) sem.release();
    }
  }
}
package com.example.lms.service.onnx;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.onnx.OnnxRerankSemaphore
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.example.lms.service.onnx.OnnxRerankSemaphore
role: config
*/
public class OnnxRerankSemaphore {
  private final Semaphore sem;
  private final long queueTimeoutMs;
  private final boolean fallbackBi;

  public OnnxRerankSemaphore(
    @Value("${rerank.onnx.semaphore.max-concurrent:4}") int maxConcurrent,
    @Value("${rerank.onnx.semaphore.queue-timeout-ms:1500}") long queueTimeoutMs,
    @Value("${rerank.onnx.fallback-to-biencoder:true}") boolean fallbackBi){
    this.sem = new Semaphore(Math.max(1, maxConcurrent));
    this.queueTimeoutMs = queueTimeoutMs;
    this.fallbackBi = fallbackBi;
  }

  public <T> T guarded(Supplier<T> onnxTask, Supplier<T> biFallback) {
    try {
      if (sem.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS)) {
        try { return onnxTask.get(); }
        finally { sem.release(); }
      }
    } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    return fallbackBi && biFallback!=null ? biFallback.get() : onnxTask.get();
  }
}
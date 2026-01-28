package com.abandonware.ai.service.onnx;

import java.io.Closeable;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
/** ONNX Session Pool stub — replace with OrtSession integration. */
public final class OnnxSessionPool implements Closeable {
  private final Semaphore sem;
  public OnnxSessionPool(int maxConcurrent){ this.sem = new Semaphore(Math.max(1, maxConcurrent)); }
  public <T> T withSession(Function<Object,T> f) throws Exception {
    sem.acquire(); try { return f.apply(new Object()); } finally { sem.release(); }
  }
  @Override public void close(){}
}

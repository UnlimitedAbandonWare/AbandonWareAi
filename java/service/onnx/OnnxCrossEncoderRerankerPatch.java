package service.onnx;
import java.util.List;
import java.util.concurrent.Semaphore;
public class OnnxCrossEncoderRerankerPatch {
  private final Semaphore gate = new Semaphore(3);
  private int fallbackThresholdMs = 600;
  public void setMaxPermits(int permits) {
    if (permits >= 0) {
      gate.drainPermits();
      gate.release(permits);
    }
  }
  public void setFallbackThresholdMs(int ms){ this.fallbackThresholdMs = ms; }
  public <T> List<T> guardTopK(List<T> in, int k){
    long start = System.currentTimeMillis();
    if (!gate.tryAcquire()) return in.subList(0, Math.min(k, in.size()));
    try {
      if (System.currentTimeMillis() - start > fallbackThresholdMs) {
        return in.subList(0, Math.min(k, in.size()));
      }
      return in.subList(0, Math.min(k, in.size()));
    } finally {
      gate.release();
    }
  }
}
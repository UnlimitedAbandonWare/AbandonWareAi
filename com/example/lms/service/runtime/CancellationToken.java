package com.example.lms.service.runtime;
import java.util.concurrent.atomic.AtomicBoolean;
public final class CancellationToken {
  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  public void cancel(){ cancelled.set(true); }
  public boolean isCancelled(){ return cancelled.get(); }
}

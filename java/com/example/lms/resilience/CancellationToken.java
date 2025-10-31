
package com.example.lms.resilience;

import java.util.concurrent.atomic.AtomicBoolean;



public class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
}

package com.example.lms.context;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ActiveRequestsCounter {
    private final AtomicInteger active = new AtomicInteger(0);
    private volatile long lastRequestFinishedAt = System.currentTimeMillis();
    public void inc(){ active.incrementAndGet(); }
    public void dec(){ lastRequestFinishedAt = System.currentTimeMillis(); active.decrementAndGet(); }
    public int get(){ return Math.max(0, active.get()); }
    public long getLastRequestFinishedAt() { return lastRequestFinishedAt; }
}

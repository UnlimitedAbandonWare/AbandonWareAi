package com.example.lms.agent;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "gemini.api.free-tier.throttle", name = "enabled", havingValue = "true", matchIfMissing = false)
public class FreeTierApiThrottleService {
    private final int maxPerMinute;
    private final int maxPerDay;
    private final AtomicInteger minuteCount = new AtomicInteger();
    private final AtomicInteger dayCount = new AtomicInteger();
    private volatile Instant minuteStart = Instant.now();
    private volatile Instant dayStart = Instant.now();

    public FreeTierApiThrottleService(
            @Value("${gemini.api.free-tier.throttle.requests-per-minute:60}") int maxPerMinute,
            @Value("${gemini.api.free-tier.throttle.requests-per-day:1000}") int maxPerDay) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
        this.maxPerDay = Math.max(1, maxPerDay);
    }

    public synchronized boolean canProceed() {
        Instant now = Instant.now();
        if (now.minusSeconds(60).isAfter(minuteStart)) {
            minuteStart = now;
            minuteCount.set(0);
        }
        if (now.minusSeconds(86_400).isAfter(dayStart)) {
            dayStart = now;
            dayCount.set(0);
        }
        if (minuteCount.get() >= maxPerMinute || dayCount.get() >= maxPerDay) {
            return false;
        }
        minuteCount.incrementAndGet();
        dayCount.incrementAndGet();
        return true;
    }
}

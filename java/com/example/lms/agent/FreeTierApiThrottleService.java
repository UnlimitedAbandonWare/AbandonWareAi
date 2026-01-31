package com.example.lms.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * A simple throttle controller to respect the free tier limits of external APIs such as Gemini.
 * <p>
 * This service tracks how many API calls have been made within the current minute and day.  When the
 * configured limits are reached it will deny further calls until the window resets.  This is intended
 * to guard against exceeding the free quota and incurring unexpected charges or rate limit errors.
 * </p>
 */
@Service
@ConditionalOnProperty(prefix = "gemini.api.free-tier.throttle", name = "enabled", havingValue = "true", matchIfMissing = false)
public class FreeTierApiThrottleService {
    private static final Logger log = LoggerFactory.getLogger(FreeTierApiThrottleService.class);

    /** The maximum number of API requests allowed per minute. */
    private final int maxPerMinute;
    /** The maximum number of API requests allowed per day. */
    private final int maxPerDay;

    private final AtomicInteger minuteCount = new AtomicInteger(0);
    private final AtomicInteger dayCount = new AtomicInteger(0);
    private volatile Instant minuteWindowStart = Instant.now();
    private volatile Instant dayWindowStart = Instant.now();

    public FreeTierApiThrottleService(
            @Value("${gemini.api.free-tier.throttle.requests-per-minute:60}") int maxPerMinute,
            @Value("${gemini.api.free-tier.throttle.requests-per-day:1000}") int maxPerDay
    ) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
        this.maxPerDay = Math.max(1, maxPerDay);
    }

    /**
     * Determines whether a new API call may proceed under the current quota.  This method is
     * thread-safe and will increment the counters if the call is allowed.  It also resets the
     * counters when their respective time windows have expired.
     *
     * @return {@code true} if the caller may proceed with an API call; {@code false} otherwise
     */
    public synchronized boolean canProceed() {
        Instant now = Instant.now();
        // reset minute window if more than 60 seconds have passed
        if (now.minusSeconds(60).isAfter(minuteWindowStart)) {
            minuteWindowStart = now;
            minuteCount.set(0);
        }
        // reset day window if more than 24 hours have passed
        if (now.minusSeconds(24 * 3600).isAfter(dayWindowStart)) {
            dayWindowStart = now;
            dayCount.set(0);
        }

        // Check quotas
        if (minuteCount.get() >= maxPerMinute) {
            log.debug("[Throttle] per-minute limit reached: {}/{}", minuteCount.get(), maxPerMinute);
            return false;
        }
        if (dayCount.get() >= maxPerDay) {
            log.debug("[Throttle] per-day limit reached: {}/{}", dayCount.get(), maxPerDay);
            return false;
        }
        // increment counters and allow
        minuteCount.incrementAndGet();
        dayCount.incrementAndGet();
        return true;
    }
}
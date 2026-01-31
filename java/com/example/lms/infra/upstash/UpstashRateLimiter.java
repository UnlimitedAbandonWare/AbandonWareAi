package com.example.lms.infra.upstash;

import com.example.lms.service.web.RateLimiterPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.Duration;




/**
 * Rate limiter implementation backed by Upstash Redis.  Uses an atomic
 * increment with expiry to track request counts per bucket.  When the
 * Upstash client is disabled the limiter always allows requests.  The
 * default per-minute limit can be overridden via configuration and a
 * convenience method is provided to apply this default limit.
 */
@Component
@RequiredArgsConstructor
public class UpstashRateLimiter implements RateLimiterPort {
    private final UpstashRedisClient upstash;
    @Value("${upstash.ratelimit.per-minute:30}")
    long perMinute;

    @Override
    public Mono<Boolean> allow(String bucket, long limit, Duration window) {
        if (!upstash.enabled()) return Mono.just(true);
        return upstash.incrExpire("rl:" + bucket, window)
                .map(count -> count <= limit);
    }

    /**
     * Apply the default per-minute rate limit for the specified bucket.
     *
     * @param bucket the logical bucket name (e.g. provider id)
     * @return a Mono emitting {@code true} when the request is allowed
     */
    public Mono<Boolean> allowPerMinute(String bucket) {
        return allow(bucket, perMinute, Duration.ofMinutes(1));
    }
}
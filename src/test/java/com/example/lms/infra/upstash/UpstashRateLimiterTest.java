package com.example.lms.infra.upstash;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class UpstashRateLimiterTest {

    @Test
    void disabledClientAllowsWithoutTouchingRedis() {
        UpstashRedisClient upstash = mock(UpstashRedisClient.class);
        when(upstash.enabled()).thenReturn(false);

        UpstashRateLimiter limiter = new UpstashRateLimiter(upstash);

        assertTrue(limiter.allow("brave:web", 1, Duration.ofSeconds(1)).block());
        verify(upstash).enabled();
        verify(upstash, never()).incrExpire(anyString(), any());
    }

    @Test
    void blankBucketAndInvalidInputsUseSafeDefaults() {
        UpstashRedisClient upstash = mock(UpstashRedisClient.class);
        when(upstash.enabled()).thenReturn(true);
        when(upstash.incrExpire("rl:default", Duration.ofSeconds(1))).thenReturn(Mono.just(1L));

        UpstashRateLimiter limiter = new UpstashRateLimiter(upstash);

        assertTrue(limiter.allow("   ", 0, null).block());
        verify(upstash).incrExpire("rl:default", Duration.ofSeconds(1));
    }

    @Test
    void countAboveClampedLimitIsRejected() {
        UpstashRedisClient upstash = mock(UpstashRedisClient.class);
        when(upstash.enabled()).thenReturn(true);
        when(upstash.incrExpire("rl:provider", Duration.ofSeconds(2))).thenReturn(Mono.just(2L));

        UpstashRateLimiter limiter = new UpstashRateLimiter(upstash);

        assertFalse(limiter.allow("provider", 1, Duration.ofSeconds(2)).block());
    }
}

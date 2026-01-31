package com.example.lms.service.web;

import reactor.core.publisher.Mono;
import java.time.Duration;




/**
 * Abstraction for rate limiting web requests.  Implementations can back the
 * limiter with a distributed store (e.g. Redis) to coordinate quotas
 * across multiple instances.  Each invocation checks whether an action
 * identified by a bucket should be allowed based on the provided limit
 * and time window.  The returned {@link Mono} emits {@code true} when
 * the caller may proceed or {@code false} to signal that the request
 * should be suppressed.
 */
public interface RateLimiterPort {
    /**
     * Determine whether a call for the given bucket is allowed under the
     * specified limit and window.  Implementations must be thread-safe
     * and should ideally perform the increment and expiry atomically.
     *
     * @param bucket logical bucket name (e.g. provider id)
     * @param limit  maximum number of allowed calls within the window
     * @param window duration of the time window
     * @return a mono emitting {@code true} when the request is permitted
     */
    Mono<Boolean> allow(String bucket, long limit, Duration window);
}
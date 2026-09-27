package com.example.lms.service.web;

import java.time.Duration;
import reactor.core.publisher.Mono;

public interface RateLimiterPort {
    Mono<Boolean> allow(String bucket, long limit, Duration window);
}

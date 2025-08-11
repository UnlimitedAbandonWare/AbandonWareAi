package com.example.lms.strategy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

@Component
public class StrategyDecisionTracker {
    private final Cache<String, StrategySelectorService.Strategy> last = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(6))
            .maximumSize(10_000)
            .build();

    public void associate(String sessionKey, StrategySelectorService.Strategy s) {
        if (sessionKey != null && s != null) last.put(sessionKey, s);
    }
    public Optional<StrategySelectorService.Strategy> getLastStrategyForSession(String sessionKey) {
        return Optional.ofNullable(last.getIfPresent(sessionKey));
    }
}

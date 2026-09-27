package com.example.lms.strategy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class StrategyDecisionTracker {

    private final Cache<String, StrategySelectorService.Strategy> lastDecisionCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(6))
            .maximumSize(10_000)
            .build();

    public void associate(String sessionKey, StrategySelectorService.Strategy strategy) {
        if (sessionKey != null && strategy != null) {
            lastDecisionCache.put(normalize(sessionKey), strategy);
        }
    }

    public Optional<StrategySelectorService.Strategy> getLastStrategyForSession(String sessionKey) {
        if (sessionKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastDecisionCache.getIfPresent(normalize(sessionKey)));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("chat-")) {
            return trimmed;
        }
        if (trimmed.matches("\\d+")) {
            return "chat-" + trimmed;
        }
        return trimmed;
    }
}

package com.example.lms.strategy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Optional;




/**
 * 각 세션(sessionKey)별로 마지막으로 어떤 검색 전략이 선택되었는지 추적합니다.
 * 이 정보는 피드백을 통해 전략의 성과를 기록할 때 사용됩니다.
 * 메모리 누수를 방지하기 위해 Caffeine 캐시를 사용하여 오래된 데이터를 자동으로 제거합니다.
 */
@Component
public class StrategyDecisionTracker {

    private final Cache<String, StrategySelectorService.Strategy> lastDecisionCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(6)) // 6시간 동안 접근 없으면 자동 삭제
            .maximumSize(10_000)                   // 최대 10,000개의 세션 정보 저장
            .build();

    /**
     * 특정 세션에 대해 선택된 전략을 기록합니다.
     *
     * @param sessionKey 세션 식별자
     * @param strategy   선택된 전략
     */
    public void associate(String sessionKey, StrategySelectorService.Strategy strategy) {
        if (sessionKey != null && strategy != null) {
            lastDecisionCache.put(normalize(sessionKey), strategy);
        }
    }

    /**
     * 특정 세션에서 마지막으로 사용된 전략을 조회합니다.
     *
     * @param sessionKey 세션 식별자
     * @return 마지막으로 사용된 전략 (Optional)
     */
    public Optional<StrategySelectorService.Strategy> getLastStrategyForSession(String sessionKey) {
        if (sessionKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastDecisionCache.getIfPresent(normalize(sessionKey)));
    }

    /**
     * 세션 키를 일관된 형식으로 정규화합니다.
     *
     * @param s 원본 세션 키
     * @return 정규화된 세션 키
     */
    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        // 이미 'chat-'으로 시작하면 그대로 반환
        if (t.startsWith("chat-")) {
            return t;
        }
        // 숫자만 있는 경우 'chat-' 접두사 추가
        if (t.matches("\\d+")) {
            return "chat-" + t;
        }
        return t;
    }
}
package com.example.lms.service.soak;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

/**
 * Soak seed configuration.
 *
 * <p>
 * Hardcoding fixed query sets in code makes 운영/검증 환경을 깨기 쉽습니다.
 * Seeds and limits are therefore configured via application.yml.
 * </p>
 */
@ConfigurationProperties(prefix = "soak.seed")
public class SoakSeedProperties {

    /** Default query limit per topic when callers don't provide an explicit k. */
    private int limit = 10;

    /** Topic -> seed query list. */
    private Map<String, List<String>> topics = new LinkedHashMap<>();

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public Map<String, List<String>> getTopics() {
        return topics;
    }

    public void setTopics(Map<String, List<String>> topics) {
        this.topics = topics;
    }

    /** Canonicalize topic aliases for backward compatibility. */
    public String canonicalTopic(String topic) {
        String t = (topic == null || topic.isBlank()) ? "all" : topic.trim();
        // Back-compat aliases (Bing is deprecated in ops).
        if ("naver-bing-fixed10".equalsIgnoreCase(t)) return "naver-fixed10";
        if ("brave-naver-fixed10".equalsIgnoreCase(t)) return "naver-brave-fixed10";
        if ("naver-brave-fixed10".equalsIgnoreCase(t)) return "naver-brave-fixed10";
        return t;
    }

    public List<String> queries(String topic) {
        String key = canonicalTopic(topic);
        List<String> q = topics.get(key);
        if (q == null) {
            // fallback: allow "all" to merge defaults if configured
            q = topics.get("all");
        }
        return q == null ? Collections.emptyList() : List.copyOf(q);
    }
}

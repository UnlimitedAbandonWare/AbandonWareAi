package com.example.lms.search.provider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Pure policies for the existing opaque snippet contract; provider order is significant. */
final class HybridSearchResultPolicy {
    private static final List<String> LOW_TRUST_URL_MARKERS = List.of(
            "namu.wiki", "tistory.com", "blog.naver.com", "cafe.naver.com",
            "dcinside.com", "ruliweb.com", "fmkorea.com", "theqoo.net", "ppomppu.co.kr",
            "youtube.com", "x.com", "twitter.com", "instagram.com");

    private HybridSearchResultPolicy() { }

    static List<String> mergeProviders(List<String> primary, List<String> secondary, int topK,
                                       boolean restrictTrust, boolean officialOnly) {
        int effectiveTopK = topK <= 0 ? 3 : Math.max(3, topK);
        List<String> selected = distinct(primary, secondary).stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(effectiveTopK)
                .toList();
        if (!restrictTrust || selected.isEmpty()) return selected;
        ArrayList<String> filtered = new ArrayList<>();
        for (String value : selected) {
            if (!isLowTrustUrl(value)) filtered.add(value);
        }
        // Explicit official-only requests must not restore rejected results.
        return filtered.isEmpty() && !officialOnly ? selected : filtered;
    }

    static List<String> mergeKeywordRetry(List<String> primary, List<String> retry, int topK) {
        LinkedHashSet<String> merged = distinct(primary, retry);
        if (merged.isEmpty()) return List.of();
        // These batches already passed their provider policy; retry uses its exact requested limit.
        return merged.stream().limit(topK).toList();
    }

    private static LinkedHashSet<String> distinct(List<String> primary, List<String> secondary) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (primary != null) merged.addAll(primary);
        if (secondary != null) merged.addAll(secondary);
        return merged;
    }

    static boolean isLowTrustUrl(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String marker : LOW_TRUST_URL_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }
}

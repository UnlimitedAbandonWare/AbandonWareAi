package com.example.lms.search;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class QueryHygieneFilter {
    private static final Pattern NON_ALNUM_KO = Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{Nd}]+");

    private QueryHygieneFilter() {}

    /**
     * 쿼리 목록에서 중복/장문을 제거하고 상한선을 적용합니다.
     * @param input 원본 쿼리 목록
     * @param max   최대 유지 개수
     * @param jaccardThreshold 자카드 유사도 임계치 (이 값 이상이면 중복으로 판정)
     */
    public static List<String> sanitize(List<String> input, int max, double jaccardThreshold) {
        if (input == null) return List.of();

        List<String> base = input.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(QueryHygieneFilter::shorten)
                .collect(Collectors.toList());

        List<String> kept = new ArrayList<>();
        List<Set<String>> keptTokens = new ArrayList<>();

        for (String q : base) {
            Set<String> currentTokens = tokens(q);
            boolean isDuplicate = false;
            for (Set<String> existingTokens : keptTokens) {
                if (jaccard(existingTokens, currentTokens) >= jaccardThreshold) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                kept.add(q);
                keptTokens.add(currentTokens);
            }
            if (kept.size() >= Math.max(1, max)) break;
        }
        return kept;
    }

    private static String shorten(String s) {
        return (s.length() > 128) ? s.substring(0, 128) : s;
    }

    private static Set<String> tokens(String s) {
        String cleaned = NON_ALNUM_KO.matcher(s.toLowerCase()).replaceAll(" ").trim();
        if (cleaned.isEmpty()) return Set.of();
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(w -> !w.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);

        Set<String> union = new HashSet<>(a);
        union.addAll(b);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
}
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

    /**
     * 도메인에 따라 적절한 상한 및 유사도 임계치를 적용하여 쿼리 목록을 정제합니다.
     * GENERAL 도메인은 6개의 쿼리와 자카드 임계치 0.60을 사용하며, 그 외 도메인은
     * 4개의 쿼리와 임계치 0.80을 사용합니다.
     *
     * @param input  원본 쿼리 목록
     * @param domain 추정 도메인(GENERAL, GENSHIN, EDUCATION 등)
     * @return 정제된 쿼리 목록
     */
    public static List<String> sanitizeForDomain(List<String> input, String domain) {
        boolean isGeneral = domain != null && "GENERAL".equalsIgnoreCase(domain);
        int max = isGeneral ? 6 : 4;
        double jaccardThreshold = isGeneral ? 0.60 : 0.80;
        return sanitize(input, max, jaccardThreshold);
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
    /** 주제(ko/en)가 쿼리에 없으면 앵커를 삽입한 뒤 위생 정제 */
    public static List<String> sanitizeAnchored(
            List<String> input, int max, double jaccardThreshold,
            String subjectPrimary, String subjectAlias) {

        // 1차 위생 정제
        List<String> base = sanitize(input, max, jaccardThreshold);
        if (base.isEmpty()) return base;

        String ko = Objects.toString(subjectPrimary, "").trim();
        String en = Objects.toString(subjectAlias, "").trim();

        // ko/en 어느 하나도 포함되지 않은 쿼리에 앵커를 앞에 붙인다.
        List<String> anchored = base.stream().map(q -> {
            String l = q.toLowerCase();
            boolean hasKo = !ko.isBlank() && l.contains(ko.toLowerCase());
            boolean hasEn = !en.isBlank() && l.contains(en.toLowerCase());
            if (hasKo || hasEn) return q;

            String prefix = "";
            if (!ko.isBlank()) prefix += ko + " ";
            if (!en.isBlank()) prefix += "\"" + en + "\" ";
            return (prefix + q).trim();
        }).distinct().collect(Collectors.toList());

        // 앵커 삽입 후 다시 위생 정제(중복/상한 적용)
        return sanitize(anchored, max, jaccardThreshold);
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
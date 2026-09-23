package com.example.lms.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class QueryHygieneFilter {
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{Nd}]+");

    private QueryHygieneFilter() {
    }

    public static List<String> sanitize(List<String> input, int max, double jaccardThreshold) {
        if (input == null) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        List<Set<String>> keptTokens = new ArrayList<>();
        int limit = Math.max(1, max);
        for (String value : input) {
            if (value == null) {
                continue;
            }
            String query = shorten(value.trim());
            if (query.isEmpty()) {
                continue;
            }
            Set<String> current = tokens(query);
            boolean duplicate = keptTokens.stream().anyMatch(existing -> jaccard(existing, current) >= jaccardThreshold);
            if (!duplicate) {
                kept.add(query);
                keptTokens.add(current);
            }
            if (kept.size() >= limit) {
                break;
            }
        }
        return kept;
    }

    public static List<String> sanitizeForDomain(List<String> input, String domain) {
        boolean general = domain != null && "GENERAL".equalsIgnoreCase(domain);
        return sanitize(input, general ? 6 : 4, general ? 0.60d : 0.80d);
    }

    public static List<String> sanitizeAnchored(
            List<String> input,
            int max,
            double jaccardThreshold,
            String subjectPrimary,
            String subjectAlias
    ) {
        List<String> base = sanitize(input, max, jaccardThreshold);
        if (base.isEmpty()) {
            return base;
        }
        String primary = Objects.toString(subjectPrimary, "").trim();
        String alias = Objects.toString(subjectAlias, "").trim();
        List<String> anchored = base.stream().map(query -> {
            String lowered = query.toLowerCase(Locale.ROOT);
            boolean hasPrimary = !primary.isBlank() && lowered.contains(primary.toLowerCase(Locale.ROOT));
            boolean hasAlias = !alias.isBlank() && lowered.contains(alias.toLowerCase(Locale.ROOT));
            if (hasPrimary || hasAlias) {
                return query;
            }
            String prefix = "";
            if (!primary.isBlank()) {
                prefix += primary + " ";
            }
            if (!alias.isBlank()) {
                prefix += "\"" + alias + "\" ";
            }
            return (prefix + query).trim();
        }).distinct().collect(Collectors.toList());
        return sanitize(anchored, max, jaccardThreshold);
    }

    private static String shorten(String value) {
        return value.length() > 128 ? value.substring(0, 128) : value;
    }

    private static Set<String> tokens(String value) {
        String cleaned = NON_WORD.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0d;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0d : (double) intersection.size() / union.size();
    }
}

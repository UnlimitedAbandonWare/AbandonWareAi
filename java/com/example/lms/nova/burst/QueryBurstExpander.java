package com.example.lms.nova.burst;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;



public class QueryBurstExpander {
    public List<String> expand(String seed, int minN, int maxN) {
        int min = Math.max(1, Math.min(minN, 32));
        int max = Math.max(min, Math.min(maxN, 32));

        String base = sanitize(seed);
        if (base.isBlank()) {
            return List.of();
        }

        Set<String> out = new LinkedHashSet<>();
        out.add(base);
        out.add(stripTrailingFiller(base));

        boolean hasKo = base.matches(".*[가-힣].*");
        boolean hasEn = base.matches(".*[A-Za-z].*");

        // Lightweight heuristic expansions (LLM-free): suffix/prefix variants.
        if (hasKo) {
            addSuffixes(out, base, List.of("공식", "발표", "출시", "출시일", "가격", "스펙", "사양", "비교", "리뷰", "후기", "루머", "소문"), max);
        }
        if (hasEn) {
            addSuffixes(out, base, List.of("official", "announcement", "release", "release date", "price", "specs", "review", "rumor", "vs"), max);
        }

        // A few cross-language variants for common patterns.
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.contains("트라이폴드") || lower.contains("trifold") || lower.contains("tri-fold") || lower.contains("three-fold")) {
            out.add("갤럭시 트라이폴드 루머");
            out.add("Galaxy trifold rumor");
            out.add("tri-fold foldable phone rumor");
        }

        // Ensure at least minN outputs (fail-soft; do not invent too much).
        if (out.size() < min) {
            if (hasKo) {
                out.add(base + " 정보");
                out.add(base + " 정리");
            } else {
                out.add(base + " info");
                out.add(base + " summary");
            }
        }

        List<String> list = new ArrayList<>();
        for (String s : out) {
            if (s == null) continue;
            String t = sanitize(s);
            if (!t.isBlank()) {
                list.add(t);
            }
            if (list.size() >= max) break;
        }
        // Enforce min/max bounds
        if (list.size() > max) {
            return list.subList(0, max);
        }
        return list;
    }

    private static void addSuffixes(Set<String> out, String base, List<String> suffixes, int max) {
        if (out.size() >= max) return;
        for (String suf : suffixes) {
            if (out.size() >= max) break;
            if (suf == null || suf.isBlank()) continue;
            // Avoid duplicating when suffix already included.
            if (base.contains(suf)) continue;
            out.add(base + " " + suf);
        }
    }

    private static String stripTrailingFiller(String s) {
        if (s == null) return "";
        String t = s.trim();
        // Korean request-y suffixes
        t = t.replaceAll("\\s+(알려줘|알려\\s*줘|설명해줘|정리해줘|찾아줘|추천해줘|해줘|해주세요)\\s*$", "");
        t = t.replaceAll("\\s*(나온다던데|나온다는데|나온대|나오나요|나온거야)\\s*$", "");
        // English request-y suffixes
        t = t.replaceAll("(?i)\\s+(tell me|explain|summarize|find|search)\\s*$", "");
        return sanitize(t);
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", " ");
        t = t.replaceAll("\\s{2,}", " ").trim();
        if (t.length() > 200) {
            t = t.substring(0, 200).trim();
        }
        return t;
    }
}
package com.example.lms.search.policy;

import com.abandonware.ai.agent.integrations.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * Deterministic "stochastic" query expander.
 *
 * <p>We use a seed derived from the query text so that the same input produces the same expansions.
 * This avoids cross-step drift while still introducing variety across different queries.
 */
public final class StochasticExpander {

    private StochasticExpander() {
    }

    public static List<String> expand(String query, SearchPolicyMode mode, int maxExpansions) {
        String q = Objects.toString(query, "").trim();
        if (q.isBlank() || maxExpansions <= 0) {
            return List.of();
        }

        long seed = stableSeed(q, mode);
        Random rnd = new Random(seed);

        List<String> suffixes = switch (mode) {
            case PRECISION -> List.of(
                    "공식",
                    "docs",
                    "documentation",
                    "release notes",
                    "spec");
            case RECALL -> List.of(
                    "정리",
                    "요약",
                    "가이드",
                    "사용법",
                    "tutorial",
                    "examples",
                    "docs",
                    "reference",
                    "release notes",
                    "changelog");
            case DISAMBIGUATE -> List.of(
                    "뜻",
                    "의미",
                    "정의",
                    "what is",
                    "difference",
                    "vs",
                    "비교",
                    "차이");
            case BALANCED, OFF -> List.of(
                    "요약",
                    "정리",
                    "docs",
                    "guide");
        };

        List<String> out = new ArrayList<>();

        // 1) Quote exact phrase (helps precision) - only when short enough.
        if (q.length() <= 80 && q.contains(" ")) {
            out.add('"' + q + '"');
        }

        // 2) Add suffix expansions
        int budget = maxExpansions;
        while (budget > 0 && out.size() < maxExpansions) {
            String suf = suffixes.get(rnd.nextInt(suffixes.size()));
            String candidate = q + " " + suf;
            if (!equalsLoose(candidate, q)) {
                out.add(candidate);
            }
            budget--;
        }

        // 3) Token-focused variants
        List<String> toks = TextUtils.tokenize(q);
        if (!toks.isEmpty() && out.size() < maxExpansions) {
            int take = Math.min(4, toks.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < take; i++) {
                if (i > 0) sb.append(' ');
                sb.append(toks.get(i));
            }
            String tokenQuery = sb.toString();
            if (!tokenQuery.isBlank() && !equalsLoose(tokenQuery, q)) {
                out.add(tokenQuery);
            }
        }

        // Trim to maxExpansions
        if (out.size() > maxExpansions) {
            return List.copyOf(out.subList(0, maxExpansions));
        }
        return List.copyOf(out);
    }

    private static long stableSeed(String q, SearchPolicyMode mode) {
        String key = mode.name() + "|" + q.toLowerCase(Locale.ROOT);
        String h = TextUtils.sha1(key);
        // Use lower 16 hex chars as a signed long-ish seed.
        long seed = 0L;
        for (int i = Math.max(0, h.length() - 16); i < h.length(); i++) {
            char c = h.charAt(i);
            int v;
            if (c >= '0' && c <= '9') v = (c - '0');
            else if (c >= 'a' && c <= 'f') v = 10 + (c - 'a');
            else if (c >= 'A' && c <= 'F') v = 10 + (c - 'A');
            else v = 0;
            seed = (seed << 4) ^ v;
        }
        return seed;
    }

    private static boolean equalsLoose(String a, String b) {
        if (a == null || b == null) return false;
        String na = a.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String nb = b.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return na.equals(nb);
    }
}

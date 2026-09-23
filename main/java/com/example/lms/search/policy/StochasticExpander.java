package com.example.lms.search.policy;

import com.abandonware.ai.agent.integrations.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
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
        if (isAsciiSearchable(q)) {
            suffixes = suffixes.stream().filter(StochasticExpander::isAsciiSearchable).toList();
        }

        List<String> out = new ArrayList<>();

        // Quote exact phrase to preserve conservative verification before broader facets.
        if (q.length() <= 80 && q.contains(" ")) {
            out.add('"' + q + '"');
        }

        int budget = maxExpansions;
        while (budget > 0 && out.size() < maxExpansions) {
            String suffix = suffixes.get(rnd.nextInt(suffixes.size()));
            String candidate = q + " " + suffix;
            if (!equalsLoose(candidate, q)) {
                out.add(candidate);
            }
            budget--;
        }

        List<String> tokens = TextUtils.tokenize(q);
        if (!tokens.isEmpty() && out.size() < maxExpansions) {
            int take = Math.min(4, tokens.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < take; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(tokens.get(i));
            }
            String tokenQuery = sb.toString();
            if (!tokenQuery.isBlank() && !equalsLoose(tokenQuery, q)) {
                out.add(tokenQuery);
            }
        }

        if (out.size() > maxExpansions) {
            return List.copyOf(out.subList(0, maxExpansions));
        }
        return List.copyOf(out);
    }

    static List<String> expandCreative(
            String query,
            SearchPolicyMode mode,
            int maxExpansions,
            String requestedOptionsHash) {
        String q = Objects.toString(query, "").trim();
        if (q.isBlank() || maxExpansions <= 0) {
            return List.of();
        }
        List<String> suffixes = new ArrayList<>(suffixes(mode));
        Collections.shuffle(suffixes, new Random(stableSeedFromOptions(requestedOptionsHash, mode)));
        List<String> out = new ArrayList<>();
        for (String suffix : suffixes) {
            String candidate = q + " " + suffix;
            if (!equalsLoose(candidate, q)) {
                out.add(candidate);
            }
            if (out.size() >= maxExpansions) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static long stableSeed(String q, SearchPolicyMode mode) {
        String key = mode.name() + "|" + q.toLowerCase(Locale.ROOT);
        String hash = TextUtils.sha1(key);
        long seed = 0L;
        for (int i = Math.max(0, hash.length() - 16); i < hash.length(); i++) {
            char c = hash.charAt(i);
            int v;
            if (c >= '0' && c <= '9') {
                v = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                v = 10 + (c - 'a');
            } else if (c >= 'A' && c <= 'F') {
                v = 10 + (c - 'A');
            } else {
                v = 0;
            }
            seed = (seed << 4) ^ v;
        }
        return seed;
    }

    private static long stableSeedFromOptions(String requestedOptionsHash, SearchPolicyMode mode) {
        String safeHash = Objects.toString(requestedOptionsHash, "hash:unknown");
        return stableSeed(safeHash, mode == null ? SearchPolicyMode.OFF : mode);
    }

    private static List<String> suffixes(SearchPolicyMode mode) {
        return switch (mode == null ? SearchPolicyMode.OFF : mode) {
            case PRECISION -> List.of("official docs", "documentation", "release notes", "spec", "primary source");
            case RECALL -> List.of("summary", "guide", "implementation guide", "usage examples", "tutorial",
                    "examples", "docs", "reference", "release notes", "changelog", "counterexample", "failure modes");
            case DISAMBIGUATE -> List.of("meaning", "definition", "overview", "what is", "difference", "vs",
                    "compare", "comparison");
            case BALANCED, OFF -> List.of("summary", "guide", "docs", "overview", "examples", "counterexample");
        };
    }

    private static boolean equalsLoose(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = a.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String nb = b.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return na.equals(nb);
    }

    private static boolean isAsciiSearchable(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 32 || ch > 126) {
                return false;
            }
        }
        return true;
    }
}

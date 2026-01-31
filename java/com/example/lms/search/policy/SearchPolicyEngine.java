package com.example.lms.search.policy;

import com.abandonware.ai.agent.integrations.TextUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * SearchPolicy engine.
 *
 * <p>This component is intentionally "low ceremony": it does not call LLMs.
 * It only adjusts breadth and generates deterministic variants (slices/expansions)
 * based on lightweight heuristics and optional overrides.
 */
@Component
public class SearchPolicyEngine {

    // Hard caps (safety guard)
    private static final int ABS_MAX_QUERIES = 16;
    private static final int MIN_TOPK = 3;
    private static final int MAX_TOPK = 24;

    public SearchPolicyDecision decide(String query, Map<String, Object> metaHints) {
        String q = Objects.toString(query, "").trim();
        Map<String, Object> meta = (metaHints == null) ? Map.of() : metaHints;

        // Explicit override
        SearchPolicyMode override = parseMode(meta.get("searchPolicyMode"));
        if (override == null) override = parseMode(meta.get("search.policy.mode"));
        if (override == null) override = parseMode(meta.get("searchPolicy.mode"));

        boolean strike = boolish(meta.get("strikeMode"));
        boolean bypass = boolish(meta.get("bypassMode"));
        boolean compression = boolish(meta.get("compressionMode"));
        boolean nightmare = boolish(meta.get("nightmareMode"));

        if (override != null) {
            return forMode(override, "override");
        }

        if (strike || bypass || compression || nightmare) {
            return forMode(SearchPolicyMode.PRECISION, "guard/cheap-mode");
        }

        // Lightweight intent heuristics
        String lower = q.toLowerCase(Locale.ROOT);
        int tokCount = TextUtils.tokenize(q).size();

        if (containsAny(lower, "최신", "최근", "업데이트", "release", "changelog", "patch", "변경사항", "버전", "릴리즈")) {
            return forMode(SearchPolicyMode.RECALL, "recency");
        }

        if (containsAny(lower, "뜻", "의미", "정의", "difference", "vs", "비교", "차이")) {
            return forMode(SearchPolicyMode.DISAMBIGUATE, "disambiguate");
        }

        if (containsAny(lower, "공식", "근거", "출처", "citation", "source", "정확")) {
            return forMode(SearchPolicyMode.PRECISION, "precision-keyword");
        }

        if (tokCount <= 2 && q.length() <= 16) {
            return forMode(SearchPolicyMode.DISAMBIGUATE, "short-query");
        }

        return forMode(SearchPolicyMode.BALANCED, "default");
    }

    /**
     * Tune the planner hint used for SmartQueryPlanner.
     */
    public int tunePlannerMaxQueries(int base, SearchPolicyDecision d) {
        int b = clamp(base, 1, 32);
        if (d == null) {
            return b;
        }
        return switch (d.mode()) {
            case OFF -> b;
            case PRECISION -> Math.min(b, 4);
            case BALANCED -> Math.max(b, 2);
            case RECALL, DISAMBIGUATE -> Math.max(b, 8);
        };
    }

    /**
     * Apply slicing + expansion on top of a base planned list.
     */
    public List<String> apply(List<String> basePlanned, String originalQuery, SearchPolicyDecision d) {
        if (d == null || d.mode() == SearchPolicyMode.OFF) {
            return (basePlanned == null) ? List.of() : List.copyOf(basePlanned);
        }

        int cap = clamp(d.maxFinalQueries(), 1, ABS_MAX_QUERIES);
        LinkedHashMap<String, String> out = new LinkedHashMap<>();

        // 0) Base planned queries first
        if (basePlanned != null) {
            for (String q : basePlanned) {
                putDedup(out, q);
                if (out.size() >= cap) break;
            }
        }

        // 1) Query slicing (only when multi-sentence)
        if (d.slicingEnabled() && out.size() < cap) {
            List<String> slices = QuerySlicer.slice(originalQuery, d.sliceWindowSentences(), d.sliceOverlapSentences(), d.maxSlices());
            for (String s : slices) {
                putDedup(out, s);
                if (out.size() >= cap) break;
            }
        }

        // 2) Deterministic stochastic expansion
        if (d.expansionEnabled() && d.maxExpansions() > 0 && out.size() < cap) {
            List<String> ex = StochasticExpander.expand(originalQuery, d.mode(), d.maxExpansions());
            for (String e : ex) {
                putDedup(out, e);
                if (out.size() >= cap) break;
            }
        }

        return List.copyOf(out.values());
    }

    public int tuneTopK(int baseTopK, SearchPolicyDecision d) {
        int b = clamp(baseTopK, MIN_TOPK, MAX_TOPK);
        if (d == null || d.mode() == SearchPolicyMode.OFF) {
            return b;
        }
        double mul = d.webTopKMultiplier();
        int tuned = (int) Math.round(b * mul);
        return clamp(tuned, MIN_TOPK, MAX_TOPK);
    }

    public int tuneVecTopK(int baseTopK, SearchPolicyDecision d) {
        int b = clamp(baseTopK, MIN_TOPK, MAX_TOPK);
        if (d == null || d.mode() == SearchPolicyMode.OFF) {
            return b;
        }
        double mul = d.vecTopKMultiplier();
        int tuned = (int) Math.round(b * mul);
        return clamp(tuned, MIN_TOPK, MAX_TOPK);
    }

    public Map<String, Object> enrichMeta(Map<String, Object> meta, SearchPolicyDecision d) {
        if (meta == null) {
            meta = new HashMap<>();
        }
        if (d != null) {
            meta.put("searchPolicyMode", d.mode().name());
            meta.put("searchPolicy.reason", d.reason());
            meta.put("searchPolicy.slicing", String.valueOf(d.slicingEnabled()));
            meta.put("searchPolicy.expansion", String.valueOf(d.expansionEnabled()));
        }
        return meta;
    }

    private static SearchPolicyDecision forMode(SearchPolicyMode mode, String reason) {
        String r = (reason == null) ? "" : reason;
        return switch (mode) {
            case OFF -> SearchPolicyDecision.off(r);
            case PRECISION -> new SearchPolicyDecision(
                    SearchPolicyMode.PRECISION,
                    false,
                    false,
                    6,
                    2,
                    1,
                    4,
                    0,
                    0.85,
                    0.85,
                    r);
            case BALANCED -> new SearchPolicyDecision(
                    SearchPolicyMode.BALANCED,
                    true,
                    true,
                    10,
                    2,
                    1,
                    4,
                    2,
                    1.0,
                    1.0,
                    r);
            case RECALL -> new SearchPolicyDecision(
                    SearchPolicyMode.RECALL,
                    true,
                    true,
                    14,
                    2,
                    1,
                    5,
                    4,
                    1.35,
                    1.20,
                    r);
            case DISAMBIGUATE -> new SearchPolicyDecision(
                    SearchPolicyMode.DISAMBIGUATE,
                    true,
                    true,
                    12,
                    2,
                    1,
                    5,
                    3,
                    1.20,
                    1.10,
                    r);
        };
    }

    private static boolean containsAny(String lower, String... needles) {
        if (lower == null || lower.isBlank() || needles == null) return false;
        for (String n : needles) {
            if (n == null || n.isBlank()) continue;
            if (lower.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static void putDedup(LinkedHashMap<String, String> out, String q) {
        if (out == null) return;
        String s = Objects.toString(q, "").trim();
        if (s.isBlank()) return;
        String key = TextUtils.normalizeQueryKey(s);
        if (key.isBlank()) return;
        // Keep the first occurrence to preserve stability.
        out.putIfAbsent(key, s);
    }

    private static boolean boolish(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim();
        if (s.isBlank()) return false;
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    private static SearchPolicyMode parseMode(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isBlank()) return null;
        try {
            return SearchPolicyMode.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

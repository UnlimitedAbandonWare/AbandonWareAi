package com.example.lms.search.policy;

import com.abandonware.ai.agent.integrations.TextUtils;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
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

    private static final String CREATIVE_OPTIONS_HASH_TRACE = "search.policy.creative.requestedOptionsHash";

    // Hard caps (safety guard)
    private static final int ABS_MAX_QUERIES = 16;
    private static final int MIN_TOPK = 3;
    private static final int MAX_TOPK = 24;

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null ? "unknown" : failure.getClass().getSimpleName();
        TraceStore.put("searchPolicy.suppressed." + safeStage, true);
        TraceStore.put("searchPolicy.suppressed." + safeStage + ".errorType", errorType);
    }

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
            return withCreativeProfile(forMode(override, "override"), meta);
        }

        if (strike || bypass || compression || nightmare) {
            return withCreativeProfile(forMode(SearchPolicyMode.PRECISION, "guard/cheap-mode"), meta);
        }

        SearchPolicyMode uiMode = policyModeFromSearchMode(meta.get("searchMode"));
        if (uiMode == null) uiMode = policyModeFromSearchMode(meta.get("search_mode"));
        if (uiMode != null) {
            return withCreativeProfile(forMode(uiMode, "ui-search-mode"), meta);
        }

        // Lightweight intent heuristics
        String lower = q.toLowerCase(Locale.ROOT);
        int tokCount = TextUtils.tokenize(q).size();

        if (containsAny(lower, "최신", "최근", "업데이트", "release", "changelog", "patch", "변경사항", "버전", "릴리즈")) {
            return withCreativeProfile(forMode(SearchPolicyMode.RECALL, "recency"), meta);
        }

        if (containsAny(lower, "뜻", "의미", "정의", "difference", "vs", "비교", "차이")) {
            return withCreativeProfile(forMode(SearchPolicyMode.DISAMBIGUATE, "disambiguate"), meta);
        }

        if (containsAny(lower, "공식", "근거", "출처", "citation", "source", "정확")) {
            return withCreativeProfile(forMode(SearchPolicyMode.PRECISION, "precision-keyword"), meta);
        }

        if (tokCount <= 2 && q.length() <= 16) {
            return withCreativeProfile(forMode(SearchPolicyMode.DISAMBIGUATE, "short-query"), meta);
        }

        return withCreativeProfile(forMode(SearchPolicyMode.BALANCED, "default"), meta);
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
        String creativeOptionsHash = creativeRequestedOptionsHash(d);
        boolean creative = d.expansionEnabled()
                && isCreativeProfile(d.rewriteTemperatureProfile())
                && creativeOptionsHash.matches("hash:[0-9a-f]{12}");
        int creativeExpansionBudget = creative
                ? clamp((int) Math.round(d.maxExpansions() * d.rewriteExplorationRate()), 0, d.maxExpansions())
                : 0;
        List<String> creativeExpansions = creative
                ? StochasticExpander.expandCreative(
                        originalQuery, d.mode(), creativeExpansionBudget, creativeOptionsHash)
                : List.of();
        int preExpansionLimit = Math.max(0, cap - creativeExpansions.size());

        if (creative) {
            putDedup(out, originalQuery);
            if (cap >= 2) {
                putDedup(out, officialPrimarySourceAnchor(originalQuery));
            }
        }

        // 0) Base planned queries first
        if (basePlanned != null) {
            for (String q : basePlanned) {
                putDedup(out, q);
                if (out.size() >= (creative ? preExpansionLimit : cap)) break;
            }
        }

        // 1) Query slicing (only when multi-sentence)
        if (d.slicingEnabled() && out.size() < (creative ? preExpansionLimit : cap)) {
            List<String> slices = QuerySlicer.slice(originalQuery, d.sliceWindowSentences(), d.sliceOverlapSentences(), d.maxSlices());
            for (String s : slices) {
                putDedup(out, s);
                if (out.size() >= (creative ? preExpansionLimit : cap)) break;
            }
        }

        // 2) Deterministic stochastic expansion
        if (d.expansionEnabled() && d.maxExpansions() > 0 && out.size() < cap) {
            List<String> ex = creative
                    ? creativeExpansions
                    : StochasticExpander.expand(originalQuery, d.mode(), d.maxExpansions());
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
            meta.put("searchPolicy.reason", SafeRedactor.traceLabelOrFallback(d.reason(), ""));
            meta.put("searchPolicy.slicing", String.valueOf(d.slicingEnabled()));
            meta.put("searchPolicy.expansion", String.valueOf(d.expansionEnabled()));
            String providerProfile = isCreativeProfile(d.rewriteTemperatureProfile())
                    ? "exploratory"
                    : d.rewriteTemperatureProfile();
            meta.put("searchPolicy.rewriteTemperatureProfile",
                    SafeRedactor.traceLabelOrFallback(providerProfile, "unknown"));
            meta.put("searchPolicy.rewriteValidationTemperature", d.rewriteValidationTemperature());
            meta.put("searchPolicy.rewriteExplorationTemperature", d.rewriteExplorationTemperature());
            meta.put("searchPolicy.rewriteExplorationRate", d.rewriteExplorationRate());
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
        if (key.isBlank()) {
            key = s.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }
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
        } catch (IllegalArgumentException ignore) {
            traceSuppressed("mode.parse", ignore);
            return null;
        }
    }

    private static SearchPolicyDecision withCreativeProfile(
            SearchPolicyDecision base,
            Map<String, Object> meta) {
        TraceStore.putInternal(CREATIVE_OPTIONS_HASH_TRACE, null);
        if (base == null || meta == null || !boolish(meta.get("creative.emergence.active"))) {
            return base;
        }
        if (!base.expansionEnabled() || base.mode() == SearchPolicyMode.OFF) {
            return base;
        }
        String intent = Objects.toString(meta.get("promptPose.application.intentSlot"), "").trim();
        String requestedHash = Objects.toString(
                meta.get("creative.emergence.requestedOptionsHash"), "").trim().toLowerCase(Locale.ROOT);
        if (!"explore".equals(intent)
                || boolish(meta.get("privacy.boundary.enforce"))
                || !requestedHash.matches("hash:[0-9a-f]{12}")) {
            return base;
        }
        String label = Objects.toString(meta.get("creative.emergence.profile"), "").trim().toUpperCase(Locale.ROOT);
        double temperature = finiteDouble(meta.get("creative.emergence.search.temperature"), Double.NaN);
        double rate = finiteDouble(meta.get("creative.emergence.search.rate"), Double.NaN);
        double candidateTemperature = finiteDouble(
                meta.get("creative.emergence.candidate.temperature"), Double.NaN);
        double candidateTopP = finiteDouble(meta.get("creative.emergence.candidate.topP"), Double.NaN);
        double finalTemperature = finiteDouble(meta.get("creative.emergence.final.temperature"), Double.NaN);
        double finalTopP = finiteDouble(meta.get("creative.emergence.final.topP"), Double.NaN);
        double selfAskTemperature = finiteDouble(
                meta.get("creative.emergence.selfAsk.temperature"), Double.NaN);
        if (!validCreativeProfile(
                label,
                temperature,
                rate,
                candidateTemperature,
                candidateTopP,
                finalTemperature,
                finalTopP,
                selfAskTemperature)) {
            return base;
        }
        TraceStore.putInternal(CREATIVE_OPTIONS_HASH_TRACE, requestedHash);
        return new SearchPolicyDecision(
                base.mode(),
                base.slicingEnabled(),
                base.expansionEnabled(),
                base.maxFinalQueries(),
                base.sliceWindowSentences(),
                base.sliceOverlapSentences(),
                base.maxSlices(),
                base.maxExpansions(),
                base.webTopKMultiplier(),
                base.vecTopKMultiplier(),
                base.reason(),
                base.rewriteValidationTemperature(),
                temperature,
                rate,
                "creative:" + label);
    }

    private static boolean validCreativeProfile(
            String label,
            double searchTemperature,
            double searchRate,
            double candidateTemperature,
            double candidateTopP,
            double finalTemperature,
            double finalTopP,
            double selfAskTemperature) {
        return switch (label) {
            case "VIVID" -> inRange(searchTemperature, 0.85d, 0.90d)
                    && inRange(searchRate, 0.70d, 0.76d)
                    && inRange(candidateTemperature, 1.10d, 1.25d)
                    && inRange(candidateTopP, 0.95d, 0.97d)
                    && inRange(finalTemperature, 1.05d, 1.20d)
                    && inRange(finalTopP, 0.95d, 0.97d)
                    && inRange(selfAskTemperature, 0.80d, 0.88d);
            case "WILD" -> inRange(searchTemperature, 0.91d, 0.97d)
                    && inRange(searchRate, 0.77d, 0.83d)
                    && inRange(candidateTemperature, 1.26d, 1.45d)
                    && inRange(candidateTopP, 0.97d, 0.99d)
                    && inRange(finalTemperature, 1.21d, 1.40d)
                    && inRange(finalTopP, 0.97d, 0.99d)
                    && inRange(selfAskTemperature, 0.89d, 0.97d);
            case "FERAL" -> inRange(searchTemperature, 0.98d, 1.00d)
                    && inRange(searchRate, 0.84d, 0.85d)
                    && inRange(candidateTemperature, 1.46d, 1.50d)
                    && inRange(candidateTopP, 0.99d, 1.00d)
                    && inRange(finalTemperature, 1.41d, 1.50d)
                    && inRange(finalTopP, 0.99d, 1.00d)
                    && inRange(selfAskTemperature, 0.98d, 1.00d);
            default -> false;
        };
    }

    private static boolean isCreativeProfile(String value) {
        return value != null && value.matches("creative:(VIVID|WILD|FERAL)");
    }

    private static boolean inRange(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    private static double finiteDouble(Object value, double fallback) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        try {
            double parsed = Double.parseDouble(Objects.toString(value, ""));
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String officialPrimarySourceAnchor(String originalQuery) {
        String q = Objects.toString(originalQuery, "").trim();
        if (q.isBlank()) {
            return "";
        }
        boolean korean = q.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
        return q + (korean ? " 공식 1차 자료" : " official primary source");
    }

    private static String creativeRequestedOptionsHash(SearchPolicyDecision decision) {
        String safe = Objects.toString(TraceStore.get(CREATIVE_OPTIONS_HASH_TRACE), "").trim().toLowerCase(Locale.ROOT);
        if (safe.matches("hash:[0-9a-f]{12}")) {
            return safe;
        }
        return "";
    }

    private static SearchPolicyMode policyModeFromSearchMode(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "OFF" -> SearchPolicyMode.OFF;
            case "FORCE_LIGHT" -> SearchPolicyMode.PRECISION;
            case "FORCE_DEEP" -> SearchPolicyMode.RECALL;
            default -> null;
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

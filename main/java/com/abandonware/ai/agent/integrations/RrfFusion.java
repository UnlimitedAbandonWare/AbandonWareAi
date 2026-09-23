package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.*;

/**
 * Weighted Reciprocal Rank Fusion (RRF) for two sources: local + web.
 * <p>
 * This replacement fixes previous brace/return placement errors that caused
 * "illegal start of type" during compilation.
 * The implementation is intentionally self-contained and conservative.
 */
public final class RrfFusion {

    private static final double DEFAULT_K = 60.0d;
    private static final double DEFAULT_LOCAL_WEIGHT = 1.0d;
    private static final double DEFAULT_WEB_WEIGHT = 1.0d;

    private RrfFusion() {}

    /**
     * Fuse two ranked lists using weighted RRF.
     * Each element is a Map with optional keys: id, url, title, snippet, source, score, rank.
     * Dedupe is performed by a canonicalized URL when present, otherwise by id.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> fuse(List<Map<String,Object>> local, List<Map<String,Object>> web) {
        return fuse(
                local,
                web,
                System.getenv("RRF_K"),
                System.getenv("RRF_W_LOCAL"),
                System.getenv("RRF_W_WEB"));
    }

    static List<Map<String,Object>> fuse(
            List<Map<String,Object>> local,
            List<Map<String,Object>> web,
            String rawK,
            String rawLocalWeight,
            String rawWebWeight) {
        if (local == null) local = List.of();
        if (web == null) web = List.of();

        RrfConfig config = resolveConfig(rawK, rawLocalWeight, rawWebWeight);

        Map<String, Double> score = new LinkedHashMap<>();
        Map<String, Map<String,Object>> pick = new LinkedHashMap<>();

        // local
        int r = 1;
        for (Map<String,Object> m : local) {
            String key = keyOf(m);
            if (key == null) continue;
            pick.putIfAbsent(key, m);
            score.put(key, finiteScore(
                    score.getOrDefault(key, 0.0),
                    config.localWeight(),
                    config.k(),
                    r,
                    DEFAULT_LOCAL_WEIGHT));
            r++;
        }
        // web
        r = 1;
        for (Map<String,Object> m : web) {
            String key = keyOf(m);
            if (key == null) continue;
            pick.putIfAbsent(key, m);
            score.put(key, finiteScore(
                    score.getOrDefault(key, 0.0),
                    config.webWeight(),
                    config.k(),
                    r,
                    DEFAULT_WEB_WEIGHT));
            r++;
        }

        List<Map.Entry<String,Double>> sorted = new ArrayList<>(score.entrySet());
        sorted.sort((a,b) -> Double.compare(b.getValue(), a.getValue()));

        List<Map<String,Object>> out = new ArrayList<>(sorted.size());
        for (Map.Entry<String,Double> e : sorted) {
            Map<String,Object> m = pick.get(e.getKey());
            if (m == null) continue;
            // propagate fused score
            Map<String,Object> mm = new LinkedHashMap<>(m);
            mm.put("rrfScore", e.getValue());
            out.add(mm);
        }
        return out;
    }

    static double parseEnvDouble(String name, double def, String rawValue) {
        ParsedDouble parsed = parseRawDouble(name, def, rawValue);
        if (!parsed.valid()) {
            return def;
        }
        String reason = invalidScalarReason(name, parsed.value());
        if (reason != null) {
            traceConfigFallback(reason);
            return def;
        }
        return parsed.value();
    }

    private static RrfConfig resolveConfig(
            String rawK,
            String rawLocalWeight,
            String rawWebWeight) {
        ParsedDouble k = parseRawDouble("RRF_K", DEFAULT_K, rawK);
        ParsedDouble localWeight = parseRawDouble(
                "RRF_W_LOCAL", DEFAULT_LOCAL_WEIGHT, rawLocalWeight);
        ParsedDouble webWeight = parseRawDouble(
                "RRF_W_WEB", DEFAULT_WEB_WEIGHT, rawWebWeight);

        if (!k.valid() || !Double.isFinite(k.value()) || k.value() <= 0.0d) {
            return fallbackConfig("invalid_k");
        }
        if (!localWeight.valid() || !webWeight.valid()
                || !Double.isFinite(localWeight.value())
                || !Double.isFinite(webWeight.value())) {
            return fallbackConfig("non_finite_weight");
        }
        if (localWeight.value() < 0.0d || webWeight.value() < 0.0d) {
            return fallbackConfig("negative_weight");
        }
        double weightSum = localWeight.value() + webWeight.value();
        if (!Double.isFinite(weightSum)) {
            return fallbackConfig("non_finite_weight");
        }
        if (weightSum <= 0.0d) {
            return fallbackConfig("zero_weight_sum");
        }
        return new RrfConfig(k.value(), localWeight.value(), webWeight.value());
    }

    private static ParsedDouble parseRawDouble(String name, double def, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return new ParsedDouble(def, true);
        }
        try {
            return new ParsedDouble(Double.parseDouble(rawValue), true);
        } catch (NumberFormatException e) {
            traceSuppressed("env.double", name, rawValue, e);
            return new ParsedDouble(def, false);
        }
    }

    private static String invalidScalarReason(String name, double value) {
        if ("RRF_K".equals(name)) {
            return !Double.isFinite(value) || value <= 0.0d ? "invalid_k" : null;
        }
        if ("RRF_W_LOCAL".equals(name) || "RRF_W_WEB".equals(name)) {
            if (!Double.isFinite(value)) return "non_finite_weight";
            if (value < 0.0d) return "negative_weight";
        }
        return null;
    }

    private static RrfConfig fallbackConfig(String reason) {
        traceConfigFallback(reason);
        return new RrfConfig(DEFAULT_K, DEFAULT_LOCAL_WEIGHT, DEFAULT_WEB_WEIGHT);
    }

    private static void traceConfigFallback(String reason) {
        Object current = TraceStore.get("agent.rrf.config.fallback.count");
        int count = current instanceof Number number ? number.intValue() : 0;
        TraceStore.put("agent.rrf.config.fallback", true);
        TraceStore.put("agent.rrf.config.fallback.reason",
                SafeRedactor.traceLabelOrFallback(reason, "invalid_config"));
        TraceStore.put("agent.rrf.config.fallback.count", count + 1);
    }

    private static double finiteScore(
            double current,
            double weight,
            double k,
            int rank,
            double defaultWeight) {
        double updated = current + (weight / (k + rank));
        if (Double.isFinite(updated)) {
            return updated;
        }
        TraceStore.put("agent.rrf.score.fallback", true);
        TraceStore.put("agent.rrf.score.fallback.reason", "non_finite_score");
        Object countValue = TraceStore.get("agent.rrf.score.fallback.count");
        int count = countValue instanceof Number number ? number.intValue() : 0;
        TraceStore.put("agent.rrf.score.fallback.count", count + 1);
        return defaultWeight / (DEFAULT_K + rank);
    }

    private static String keyOf(Map<String,Object> m) {
        Object url = m.get("url");
        if (url instanceof String) {
            String u = __canonicalUrl((String) url);
            if (u != null && !u.isBlank()) return u;
        }
        Object id = m.get("id");
        return (id == null) ? null : String.valueOf(id);
    }

    /** Normalize tracking params out of URLs for dedupe stability. */
    private static String __canonicalUrl(String url) {
        if (url == null || url.isBlank()) return url;
        try {
            java.net.URI u = new java.net.URI(url);
            String q = u.getQuery();
            String filtered = null;
            if (q != null && !q.isBlank()) {
                String[] parts = q.split("&");
                StringBuilder sb = new StringBuilder();
                for (String s : parts) {
                    if (s.startsWith("utm_") || s.startsWith("fbclid")) continue;
                    if (sb.length() > 0) sb.append("&");
                    sb.append(s);
                }
                filtered = (sb.length() == 0) ? null : sb.toString();
            }
            return new java.net.URI(u.getScheme(), u.getAuthority(), u.getPath(), filtered, null).toString();
        } catch (Exception e) {
            traceSuppressed("canonicalUrl", url, e);
            return url;
        }
    }

    private static void traceSuppressed(String stage, String name, String rawValue, Throwable error) {
        TraceStore.put("agent.rrf.env.suppressed", true);
        TraceStore.put("agent.rrf.env.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.rrf.env.suppressed.errorType", "invalid_number");
        TraceStore.put("agent.rrf.env.suppressed.name",
                SafeRedactor.traceLabelOrFallback(name, "unknown"));
        TraceStore.put("agent.rrf.env.suppressed.valueHash", SafeRedactor.hashValue(rawValue));
        TraceStore.put("agent.rrf.env.suppressed.valueLength", rawValue == null ? 0 : rawValue.length());
    }

    private static void traceSuppressed(String stage, String url, Throwable error) {
        TraceStore.put("agent.rrf.url.suppressed", true);
        TraceStore.put("agent.rrf.url.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.rrf.url.suppressed.errorType", urlErrorType(error));
        TraceStore.put("agent.rrf.url.suppressed.urlHash", SafeRedactor.hashValue(url));
        TraceStore.put("agent.rrf.url.suppressed.urlLength", url == null ? 0 : url.length());
    }

    private static String urlErrorType(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        if (error instanceof java.net.URISyntaxException || error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }

    private record ParsedDouble(double value, boolean valid) {
    }

    private record RrfConfig(double k, double localWeight, double webWeight) {
    }
}

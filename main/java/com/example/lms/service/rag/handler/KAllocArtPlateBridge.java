package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.Map;

final class KAllocArtPlateBridge {

    private KAllocArtPlateBridge() {
    }

    static double[] tails(Map<String, Object> md) {
        String alias = metaString(md, "resource.artplate.alias",
                SafeRedactor.traceLabelOrFallback(TraceStore.get("artplate.gate.moeStrategy.alias"), ""));
        if (alias.isBlank()) {
            alias = SafeRedactor.traceLabelOrFallback(TraceStore.get("artplate.selector.selected"), "");
        }
        double web = metaDouble(md, "tail.web", metaDouble(md, "webTailSignal", 0.0d));
        double vector = metaDouble(md, "tail.vector", metaDouble(md, "vectorTailSignal", 0.0d));
        double kg = metaDouble(md, "tail.kg", metaDouble(md, "kgTailSignal", 0.0d));
        if (alias.isBlank()) {
            return new double[] { web, vector, kg };
        }
        md.put("resource.artplate.alias", alias);
        TraceStore.put("retrieval.kalloc.artplate.alias", alias);
        return switch (alias) {
            case "AP1_AUTH_WEB", "AP2_FRESH_WEB" -> new double[] { Math.max(web, 0.75d), vector, kg };
            case "AP3_VEC_DENSE" -> new double[] { web, Math.max(vector, 0.75d), kg };
            case "AP5_KG_REASON" -> new double[] { web, vector, Math.max(kg, 0.75d) };
            default -> new double[] { web, vector, kg };
        };
    }

    private static double metaDouble(Map<String, Object> meta, String key, double def) {
        if (meta == null) {
            return def;
        }
        Object v = meta.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                TraceStore.put("retrieval.handler.suppressed.kalloc.artplate.metaDouble", true); RetrievalHandlerTraceSuppressions.trace("kalloc.artplate.metaDouble", ignored); return def;
            }
        }
        return def;
    }

    private static String metaString(Map<String, Object> meta, String key, String def) {
        if (meta == null) {
            return def;
        }
        Object v = meta.get(key);
        String s = v == null ? "" : String.valueOf(v).trim();
        return s.isBlank() ? def : s;
    }
}

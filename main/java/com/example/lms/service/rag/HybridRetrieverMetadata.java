package com.example.lms.service.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class HybridRetrieverMetadata {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrieverMetadata.class);

    private HybridRetrieverMetadata() {
    }

    static void addCapped(Set<Content> dst, List<Content> src, int cap) {
        if (dst == null || src == null || src.isEmpty()) {
            return;
        }
        if (cap <= 0 || cap == Integer.MAX_VALUE) {
            dst.addAll(src);
            return;
        }
        for (Content c : src) {
            if (c == null) {
                continue;
            }
            dst.add(c);
            if (dst.size() >= cap) {
                break;
            }
        }
    }

    static boolean containsAny(String text, String[] cues) {
        if (text == null) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (String c : cues) {
            if (t.contains(c)) {
                return true;
            }
        }
        return false;
    }

    static double bounded01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    static Map<String, Object> toMap(Object meta) {
        if (meta == null) {
            return Map.of();
        }
        if (meta instanceof dev.langchain4j.rag.query.Metadata m) {
            Map<String, Object> out = new HashMap<>();
            try {
                Map<String, Object> inner = QueryUtils.metadata(m);
                if (inner != null) {
                    out.putAll(inner);
                }
            } catch (Exception ignore) {
                log.debug("[HybridRetrieverMetadata] fail-soft stage={}", "metadata.toMap");
                // Keep session metadata even when LangChain4j metadata conversion is unavailable.
            }
            Object sid = m.chatMemoryId();
            if (sid != null) {
                out.put(LangChainRAGService.META_SID, sid);
            }
            return out;
        }
        if (meta instanceof Map<?, ?> raw) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                Object k = e.getKey();
                if (k != null) {
                    out.put(k.toString(), e.getValue());
                }
            }
            return out;
        }
        return Map.of();
    }

    static boolean metaBool(Map<String, Object> md, String key, boolean defaultValue) {
        if (md == null || md.isEmpty() || key == null || key.isBlank()) {
            return defaultValue;
        }
        Object v = md.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return defaultValue;
        }
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "n".equals(s)) {
            return false;
        }
        return defaultValue;
    }

    static String metaString(Map<String, Object> md, String key) {
        if (md == null || md.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        Object v = md.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    static int metaInt(Map<String, Object> md, String key, int defaultValue) {
        if (md == null || md.isEmpty() || key == null || key.isBlank()) {
            return defaultValue;
        }
        Object v = md.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                log.debug("[HybridRetrieverMetadata] fail-soft stage={} errorType={}",
                        "metaInt.parse", "invalid_number");
                return defaultValue;
            }
            return n.intValue();
        }
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return defaultValue;
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException ignore) {
            log.debug("[HybridRetrieverMetadata] fail-soft stage={} errorType={}",
                    "metaInt.parse", "invalid_number");
            return defaultValue;
        }
    }

    static String buildDedupeKey(String dedupeKey, Content c) {
        String text = Optional.ofNullable(c.textSegment())
                .map(TextSegment::text)
                .orElse(c.toString());
        if ("url".equalsIgnoreCase(dedupeKey)) {
            return Optional.ofNullable(HybridRetrieverSupport.extractUrl(text)).orElse(text);
        } else if ("hash".equalsIgnoreCase(dedupeKey)) {
            return Integer.toHexString(text.hashCode());
        } else {
            return text;
        }
    }
}

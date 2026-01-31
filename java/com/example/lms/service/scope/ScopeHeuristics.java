package com.example.lms.service.scope;

import com.example.lms.service.VectorMetaKeys;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * Heuristic scope inference (WHOLE vs PART) + anchor key derivation.
 *
 * <p>Rules are intentionally simple:
 * <ul>
 *   <li>If metadata already contains scope keys -> trust it.</li>
 *   <li>Otherwise try to pick a stable anchor from metadata (entity/title/sourceId...)</li>
 *   <li>Detect simple part keywords from text.</li>
 * </ul>
 * </p>
 */
public final class ScopeHeuristics {
    private ScopeHeuristics() {
    }

    public static ScopeLabel infer(String text, Map<String, Object> meta) {
        // 1) Pre-labeled: trust metadata (best effort)
        if (meta != null) {
            String pre = asString(meta.get(VectorMetaKeys.META_SCOPE_ANCHOR_KEY));
            if (StringUtils.hasText(pre)) {
                String kind = asString(meta.get(VectorMetaKeys.META_SCOPE_KIND));
                String part = asString(meta.get(VectorMetaKeys.META_SCOPE_PART_KEY));
                return new ScopeLabel(
                        normalizeKey(pre),
                        StringUtils.hasText(kind) ? kind : "WHOLE",
                        StringUtils.hasText(part) ? normalizeKey(part) : null,
                        0.99,
                        "meta_pre_labeled"
                );
            }
        }

        // 2) Anchor candidate from meta (project-specific keys can be added)
        String anchor = "";
        String reason = "";
        if (meta != null) {
            anchor = firstNonBlank(
                    asString(meta.get(VectorMetaKeys.META_ENTITY)),
                    asString(meta.get("entity")),
                    asString(meta.get("title")),
                    asString(meta.get("sourceId")),
                    asString(meta.get("docId")),
                    // knowledge-base fields
                    asString(meta.get("kb_entity")),
                    asString(meta.get("kb_domain")),
                    asString(meta.get("kb_id")),
                    // translation-memory fields
                    asString(meta.get("tm_id")),
                    asString(meta.get("tm_field")),
                    asString(meta.get("ms_subject")),
                    // session fallbacks (last resort)
                    asString(meta.get(VectorMetaKeys.META_SID_LOGICAL)),
                    asString(meta.get(VectorMetaKeys.META_SID))
            );
            reason = StringUtils.hasText(anchor) ? "anchor_from_meta" : "no_anchor_meta";
        }
        anchor = normalizeKey(anchor);

        // 3) Part/whole inference from plain text
        String kind = "WHOLE";
        String partKey = null;
        String t = (text == null) ? "" : text.toLowerCase(Locale.ROOT);

        if (containsAny(t, "이어팁", "ear tip", "eartip")) {
            kind = "PART";
            partKey = "ear_tip";
        } else if (containsAny(t, "케이스", "case")) {
            kind = "PART";
            partKey = "case";
        } else if (containsAny(t, "바디", "몸체", "본체", "body")) {
            kind = "PART";
            partKey = "body";
        }

        // 4) Confidence scoring (tiny and conservative)
        double conf = 0.75;
        if (!StringUtils.hasText(anchor)) conf = 0.25;
        if ("PART".equalsIgnoreCase(kind) && !StringUtils.hasText(anchor)) conf = 0.20;

        return new ScopeLabel(anchor, kind, partKey, conf, reason);
    }

    private static boolean containsAny(String t, String... keys) {
        if (t == null) return false;
        for (String k : keys) {
            if (k != null && !k.isBlank() && t.contains(k)) return true;
        }
        return false;
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) return "";
        for (String x : xs) {
            if (StringUtils.hasText(x)) return x;
        }
        return "";
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * Normalize keys to match metadata constraints in common vector stores.
     */
    public static String normalizeKey(String s) {
        if (!StringUtils.hasText(s)) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9가-힣]+", "");
    }
}

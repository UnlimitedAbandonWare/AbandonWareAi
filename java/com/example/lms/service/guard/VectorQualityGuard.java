package com.example.lms.service.guard;

import com.example.lms.service.VectorMetaKeys;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Retrieval-time quality guard to suppress low-signal KB vectors (and denylisted hubs).
 *
 * <p>This runs after VectorPoisonGuard and is meant to reduce drift by filtering:
 * <ul>
 *   <li>unverified / verification_needed KB entries</li>
 *   <li>KB entries with too few URL-like citations</li>
 *   <li>KB entries with low confidence (if present)</li>
 *   <li>denylisted embedding ids (TTL)</li>
 * </ul>
 * </p>
 */
@Component
public class VectorQualityGuard {
    private static final Logger log = LoggerFactory.getLogger(VectorQualityGuard.class);

    @Value("${vector.quality.enabled:true}")
    private boolean enabled;

    @Value("${vector.quality.kb.min-citation-url-count:1}")
    private int minCitationUrlCount;

    @Value("${vector.quality.kb.min-confidence:0.55}")
    private double minKbConfidence;

    @Value("${vector.quality.kb.max-unknown-attr-ratio:0.50}")
    private double maxUnknownAttrRatio;

    @Value("${vector.quality.kb.unknown-value-tokens:unknown,unconfirmed,n/a,na,none,미상,불명,정보없음}")
    private String unknownValueTokensCsv;

    @Autowired(required = false)
    private VectorDenylistRegistry denylist;

    private volatile Pattern unknownTokenPattern;

    public <T extends TextSegment> List<EmbeddingMatch<T>> filterMatches(List<EmbeddingMatch<T>> matches, String queryText, String stage) {
        if (!enabled || matches == null || matches.isEmpty()) return matches;

        List<EmbeddingMatch<T>> out = new ArrayList<>(matches.size());
        int dropped = 0;

        for (EmbeddingMatch<T> m : matches) {
            if (m == null) continue;

            // denylist by embedding id
            String embeddingId = safe(m.embeddingId());
            if (denylist != null && denylist.isBanned(embeddingId)) {
                dropped++;
                continue;
            }

            T seg = m.embedded();
            Map<String, Object> meta = (seg == null || seg.metadata() == null) ? Map.of() : seg.metadata().toMap();

            String docType = safe(meta.getOrDefault(VectorMetaKeys.META_DOC_TYPE, ""));
            if (docType.isBlank()) {
                docType = inferDocType(seg, meta);
            }
            if (!"KB".equalsIgnoreCase(docType)) {
                out.add(m);
                continue;
            }

            boolean verificationNeeded = truthy(meta.get(VectorMetaKeys.META_VERIFICATION_NEEDED))
                    || truthy(meta.get("verification_needed"));
            boolean verified = truthy(meta.get(VectorMetaKeys.META_VERIFIED));

            if (verificationNeeded || !verified) {
                dropped++;
                continue;
            }

            int urlCitations = safeInt(meta.get(VectorMetaKeys.META_CITATION_URL_COUNT), -1);
            if (urlCitations >= 0 && urlCitations < Math.max(0, minCitationUrlCount)) {
                dropped++;
                continue;
            }

            double kbConf = safeDouble(meta.get("kb_confidence"), -1.0);
            if (kbConf >= 0.0 && kbConf < minKbConfidence) {
                dropped++;
                continue;
            }

            double unknownRatio = safeDouble(meta.get("unknown_attr_ratio"), -1.0);
            if (unknownRatio < 0.0 && seg != null) {
                unknownRatio = estimateUnknownRatioFromText(seg.text());
            }
            if (unknownRatio >= 0.0 && unknownRatio > maxUnknownAttrRatio) {
                dropped++;
                continue;
            }

            out.add(m);
        }

        if (dropped > 0) {
            log.debug("[VectorQualityGuard] stage={} dropped={} kept={} q={}", safe(stage), dropped, out.size(), safe(queryText));
        }

        return out;
    }



    /**
     * Infer docType when metadata is missing.
     *
     * <p>Conservative: only returns "KB" when strong signals are present.
     * Otherwise returns empty string to avoid misclassification.</p>
     */
    private static String inferDocType(TextSegment seg, Map<String, Object> meta) {
        if (meta != null) {
            // Strong signal: kb_domain explicitly set
            Object kbDomain = meta.get(VectorMetaKeys.META_KB_DOMAIN);
            if (kbDomain != null && !String.valueOf(kbDomain).isBlank()) {
                return "KB";
            }
        }

        // Inspect the first non-empty line for common KB markers
        String content = seg == null ? null : seg.text();
        if (content == null || content.isBlank()) {
            return "";
        }

        String[] lines = content.split("\\R", 6);
        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isEmpty()) continue;
            String lower = t.toLowerCase(Locale.ROOT);

            // Common YAML-ish KB headers
            if (lower.startsWith("kb:") || lower.startsWith("knowledge_base:") || lower.startsWith("doc_type:")) {
                return "KB";
            }

            // Some KB items include these structured fields early
            if (lower.contains("information_status:") && lower.contains("existence:")) {
                return "KB";
            }

            break;
        }

        // No strong signal
        return "";
    }

    private double estimateUnknownRatioFromText(String text) {
        if (text == null || text.isBlank()) return 0.0;

        String[] lines = text.split("\\r?\\n");
        int total = 0;
        int unknown = 0;

        Pattern p = unknownPattern();

        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isBlank()) continue;

            // Count "attr: value" lines
            int idx = t.indexOf(':');
            if (idx <= 0 || idx >= t.length() - 1) continue;

            total++;
            String value = t.substring(idx + 1).trim();
            if (value.isBlank() || p.matcher(value.toLowerCase(Locale.ROOT)).find()) {
                unknown++;
            }
        }

        if (total <= 0) return 0.0;
        return (double) unknown / (double) total;
    }

    private Pattern unknownPattern() {
        Pattern p = unknownTokenPattern;
        if (p != null) return p;

        Set<String> toks = Arrays.stream(unknownValueTokensCsv == null ? new String[0] : unknownValueTokensCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        if (toks.isEmpty()) toks = Set.of("unknown", "unconfirmed", "n/a", "na", "none");

        // simple OR regex (escaped)
        String pat = toks.stream().map(VectorQualityGuard::regexEscape).collect(Collectors.joining("|"));
        p = Pattern.compile("\\b(" + pat + ")\\b");
        unknownTokenPattern = p;
        return p;
    }

    private static String regexEscape(String s) {
        if (s == null) return "";
        return Pattern.quote(s);
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return false;
        return s.equalsIgnoreCase("true")
                || s.equalsIgnoreCase("1")
                || s.equalsIgnoreCase("yes")
                || s.equalsIgnoreCase("y")
                || s.equalsIgnoreCase("on");
    }

    private static int safeInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return def;
            return Integer.parseInt(s);
        } catch (Exception ignore) {
            return def;
        }
    }

    private static double safeDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return def;
            return Double.parseDouble(s);
        } catch (Exception ignore) {
            return def;
        }
    }

    private static String safe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}

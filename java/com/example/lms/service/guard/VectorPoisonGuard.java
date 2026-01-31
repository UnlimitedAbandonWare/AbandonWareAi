package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VectorPoisonGuard
 *
 * <p>
 * 목적:
 * - 로그/스택트레이스/오케스트레이션 디버그 덤프 텍스트가 벡터스토어에 색인되어
 * RAG 컨텍스트를 오염시키는 문제를 재발 차단한다.
 * - 이미 색인된 오염 데이터도 Retrieval 단계에서 후처리로 걸러낸다.
 *
 * <p>
 * 설계 원칙:
 * - fail-closed(차단) 쪽이 기본 (오염은 한번 들어가면 회복이 어렵다)
 * - 하지만 오탐 가능성이 있으므로 임계값은 설정으로 조절 가능
 */
@Component
public class VectorPoisonGuard {

    private static final Logger log = LoggerFactory.getLogger(VectorPoisonGuard.class);

    @Value("${vector.poison-guard.enabled:true}")
    private boolean enabled;

    @Value("${vector.poison-guard.block-log-like:true}")
    private boolean blockLogLike;

    @Value("${vector.poison-guard.sanitize-trace-dump:true}")
    private boolean sanitizeTraceDump;

    @Value("${vector.poison-guard.max-text-chars:20000}")
    private int maxTextChars;

    @Value("${vector.poison-guard.max-lines:400}")
    private int maxLines;

    @Value("${vector.poison-guard.logline-ratio-threshold:0.22}")
    private double logLineRatioThreshold;

    @Value("${vector.poison-guard.min-logline-matches:3}")
    private int minLogLineMatches;

    @Value("${vector.poison-guard.allow-legacy-no-doc-type:true}")
    private boolean allowLegacyNoDocType;

    // ──────────────────────────────────────────────────────────────────────────
    // ERO_AX_X.txt에서 관찰되는 패턴
    // ──────────────────────────────────────────────────────────────────────────

    // 타임스탬프+레벨 로그라인
    private static final Pattern TS_LEVEL_LINE = Pattern.compile(
            "(?m)^\\d{4}-\\d{2}-\\d{2}[T\\s]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s+\\b(?:TRACE|DEBUG|INFO|WARN|ERROR)\\b\\s+\\[");

    // 스택트레이스 라인
    private static final Pattern STACKTRACE_LINE = Pattern.compile(
            "(?m)^\\s*at\\s+[\\w.$]+\\([\\w.$]+:\\d+\\)");

    // Hibernate/SQL 트레이스
    private static final Pattern HIBERNATE_SQL = Pattern.compile(
            "(?is)\\borg\\.hibernate\\.SQL\\b|\\bTransactionInterceptor\\b|\\bHibernate:\\b");

    // 오케스트레이션 덤프 마커
    private static final Pattern ORCH_TRACE = Pattern.compile(
            "(?is)\\bSearch Trace\\b|\\bRaw Snippets\\b|\\bFinal Context\\b|\\bOrchestration State\\b|\\borch\\.");

    public record IngestDecision(boolean allow, String text, Map<String, Object> meta, String reason, double risk) {
    }

    public IngestDecision inspectIngest(String sid, String text, Map<String, Object> meta, String stage) {
        if (!enabled) {
            return new IngestDecision(true, text, meta, "", 0.0);
        }
        if (text == null || text.isBlank()) {
            return new IngestDecision(false, "", meta, "blank", 1.0);
        }

        String payload = text;
        if (payload.length() > Math.max(1, maxTextChars)) {
            payload = payload.substring(0, Math.max(1, maxTextChars));
        }

        int lines = countLines(payload);
        if (lines > Math.max(1, maxLines)) {
            Map<String, Object> m = ensureMutable(meta);
            m.put(VectorMetaKeys.META_DOC_TYPE, "LOG");
            m.put(VectorMetaKeys.META_POISON_REASON, "too_many_lines");
            record(stage, "too_many_lines", lines, 0, 0, sid);
            return new IngestDecision(false, "", m, "too_many_lines", 0.95);
        }

        // Orchestration trace-dump marker: block or sanitize
        if (ORCH_TRACE.matcher(payload).find()) {
            if (sanitizeTraceDump) {
                String sanitized = stripAfterFirstMatch(payload, ORCH_TRACE);
                if (sanitized != null && !sanitized.isBlank()) {
                    Map<String, Object> m = ensureMutable(meta);
                    m.put(VectorMetaKeys.META_SANITIZED, "true");
                    m.put(VectorMetaKeys.META_POISON_REASON, "orch_trace_stripped");
                    // doc_type stays as-is (caller may set KB/MEMORY/WEB); do not force LOG here
                    record(stage, "sanitize_orch_trace", lines, 0, 0, sid);
                    return new IngestDecision(true, sanitized.trim(), m, "orch_trace_stripped", 0.7);
                }
            }
            Map<String, Object> m = ensureMutable(meta);
            m.put(VectorMetaKeys.META_DOC_TYPE, "TRACE");
            m.put(VectorMetaKeys.META_POISON_REASON, "orch_trace");
            record(stage, "block_orch_trace", lines, 0, 0, sid);
            return new IngestDecision(false, "", m, "orch_trace", 0.85);
        }

        int logLines = countMatches(TS_LEVEL_LINE, payload, 200);
        int stackLines = countMatches(STACKTRACE_LINE, payload, 200);
        boolean hasSql = HIBERNATE_SQL.matcher(payload).find();

        boolean looksLoggy = (lines > 0)
                && (logLines >= Math.max(1, minLogLineMatches))
                && (((double) logLines / (double) lines) >= logLineRatioThreshold);

        boolean looksStacky = stackLines >= 2;

        if (blockLogLike && (looksLoggy || looksStacky || hasSql)) {
            String reason;
            double risk;
            if (hasSql) {
                reason = "hibernate_sql";
                risk = 0.9;
            } else if (looksStacky) {
                reason = "stacktrace_lines";
                risk = 0.95;
            } else {
                reason = "timestamp_log_lines";
                risk = 0.9;
            }
            Map<String, Object> m = ensureMutable(meta);
            m.put(VectorMetaKeys.META_DOC_TYPE, "LOG");
            m.put(VectorMetaKeys.META_POISON_REASON, reason);
            record(stage, "block_" + reason, lines, logLines, stackLines, sid);
            return new IngestDecision(false, "", m, reason, risk);
        }

        return new IngestDecision(true, payload, meta, "", 0.0);
    }

    /**
     * Retrieval-time post filter.
     *
     * <p>
     * - sid 격리 강제
     * - doc_type = LOG/TRACE 계열 차단
     * - legacy(메타 누락) 허용 여부는 설정으로 제어
     * - 내용 기반으로도 log-like면 차단
     */
    public <T extends TextSegment> List<EmbeddingMatch<T>> filterMatches(List<EmbeddingMatch<T>> matches,
            String requestedSid) {
        if (!enabled || matches == null || matches.isEmpty()) {
            return matches;
        }

        String sid = (requestedSid == null) ? "" : requestedSid.trim();
        List<EmbeddingMatch<T>> out = new ArrayList<>(matches.size());

        int dropped = 0;
        int droppedPoison = 0;

        for (EmbeddingMatch<T> m : matches) {
            if (m == null || m.embedded() == null) {
                continue;
            }
            T seg = m.embedded();

            if (!sidAllowed(seg, sid)) {
                dropped++;
                continue;
            }

            if (looksPoisonous(seg)) {
                dropped++;
                droppedPoison++;
                continue;
            }

            out.add(m);
        }

        if (dropped > 0) {
            try {
                TraceStore.put("vector.poison.dropped", dropped);
                TraceStore.put("vector.poison.dropped.poison", droppedPoison);
                TraceStore.put("vector.poison.kept", out.size());
            } catch (Exception ignore) {
            }
        }

        return out;
    }

    /**
     * Query 자체가 로그 덤프일 때는, 그대로 embed하면 로그↔로그 유사도를 강하게 유발한다.
     * 가능한 경우 "비-로그 라인"만 남겨 검색용 쿼리를 정제한다.
     */
    public String sanitizeQueryForVectorSearch(String query) {
        if (!enabled) {
            return query;
        }
        if (query == null) {
            return null;
        }
        String q = query.trim();
        if (q.isEmpty()) {
            return q;
        }

        // log-like query면 non-log line만 추출
        int lines = countLines(q);
        int logLines = countMatches(TS_LEVEL_LINE, q, 20);
        int stackLines = countMatches(STACKTRACE_LINE, q, 20);
        boolean hasSql = HIBERNATE_SQL.matcher(q).find();
        boolean hasOrch = ORCH_TRACE.matcher(q).find();

        boolean looksLogQuery = hasOrch || hasSql || stackLines >= 1 || (lines > 0 && logLines >= 1);

        if (!looksLogQuery) {
            return q;
        }

        String[] parts = q.split("\\r?\\n");
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (p == null)
                continue;
            String line = p.trim();
            if (line.isEmpty())
                continue;

            if (TS_LEVEL_LINE.matcher(line).find())
                continue;
            if (STACKTRACE_LINE.matcher(line).find())
                continue;
            if (HIBERNATE_SQL.matcher(line).find())
                continue;
            if (ORCH_TRACE.matcher(line).find())
                continue;

            kept.add(line);
            if (kept.size() >= 2)
                break;
        }

        String out = kept.isEmpty() ? q : String.join(" ", kept);
        out = out.replaceAll("\\s+", " ").trim();
        if (out.length() > 240)
            out = out.substring(0, 240);
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────────

    private boolean looksPoisonous(TextSegment seg) {
        if (seg == null) {
            return true;
        }

        String docType = "";
        try {
            if (seg.metadata() != null) {
                Object v = seg.metadata().toMap().get(VectorMetaKeys.META_DOC_TYPE);
                docType = v == null ? "" : String.valueOf(v);
            }
        } catch (Exception ignore) {
        }

        String dt = docType == null ? "" : docType.trim().toUpperCase(Locale.ROOT);
        if (!dt.isBlank()) {
            if ("LOG".equals(dt) || "TRACE".equals(dt) || "DIAGNOSTIC".equals(dt) || "STACKTRACE".equals(dt)) {
                return true;
            }
        } else if (!allowLegacyNoDocType) {
            return true;
        }

        String text = seg.text();
        if (text == null || text.isBlank()) {
            return true;
        }

        // legacy vectors: content-based detection
        int lines = countLines(text);
        int logLines = countMatches(TS_LEVEL_LINE, text, 50);
        int stackLines = countMatches(STACKTRACE_LINE, text, 50);
        boolean hasSql = HIBERNATE_SQL.matcher(text).find();
        boolean hasOrch = ORCH_TRACE.matcher(text).find();

        if (hasOrch || hasSql || stackLines >= 2) {
            return true;
        }
        if (lines > 0 && logLines >= Math.max(1, minLogLineMatches)) {
            double ratio = (double) logLines / (double) lines;
            return ratio >= logLineRatioThreshold;
        }
        return false;
    }

    
private static boolean sidAllowed(TextSegment seg, String requestedSid) {
    String segSid = "";
    String segLogicalSid = "";
    try {
        if (seg != null && seg.metadata() != null) {
            var map = seg.metadata().toMap();
            Object v = map.get(LangChainRAGServiceSidKey());
            if (v != null) {
                segSid = String.valueOf(v);
            }
            Object v2 = map.get(LangChainRAGServiceSidLogicalKey());
            if (v2 != null) {
                segLogicalSid = String.valueOf(v2);
            }
        }
    } catch (Exception ignore) {
    }
    segSid = (segSid == null) ? "" : segSid.trim();
    segLogicalSid = (segLogicalSid == null) ? "" : segLogicalSid.trim();

    final String GLOBAL_SID = "__PRIVATE__";

    String rs = (requestedSid == null) ? "" : requestedSid.trim();
    // __TRANSIENT__ is treated as "no session" (global only)
    if ("__TRANSIENT__".equalsIgnoreCase(rs)) {
        rs = "";
    }

    // When VectorSidService rotation is enabled, global pool sid can become "__PRIVATE__#...".
    // Treat both physical and logical sids as global for filtering.
    boolean segIsGlobal = GLOBAL_SID.equals(segSid)
            || segSid.startsWith(GLOBAL_SID + "#")
            || GLOBAL_SID.equals(segLogicalSid);

    // If request has no sid, accept only global pool.
    if (rs.isBlank()) {
        return segIsGlobal;
    }

    // Always accept global pool as shared KB.
    if (segIsGlobal) {
        return true;
    }

    // Direct match (physical or logical)
    if (segSid.equals(rs) || (!segLogicalSid.isBlank() && segLogicalSid.equals(rs))) {
        return true;
    }

    // Compatibility: numeric sid ↔ chat-<n>
    if (rs.matches("\\d+")) {
        if (segSid.equals("chat-" + rs) || segLogicalSid.equals("chat-" + rs)) {
            return true;
        }
    }
    if (rs.startsWith("chat-") && rs.length() > 5) {
        String tail = rs.substring(5);
        if (tail.matches("\\d+")) {
            if (segSid.equals(tail) || segLogicalSid.equals(tail)) {
                return true;
            }
        }
    }

    return false;
}

// NOTE: Avoid importing LangChainRAGService here to keep guard package light.
    private static String LangChainRAGServiceSidKey() {
        return "sid";
    }

    private static String LangChainRAGServiceSidLogicalKey() {
        return "sid_logical";
    }

    private static Map<String, Object> ensureMutable(Map<String, Object> meta) {
        if (meta == null)
            return new LinkedHashMap<>();
        if (meta instanceof LinkedHashMap)
            return meta;
        return new LinkedHashMap<>(meta);
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty())
            return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n')
                n++;
        }
        return n;
    }

    private static int countMatches(Pattern p, String s, int cap) {
        if (p == null || s == null || s.isEmpty())
            return 0;
        int c = 0;
        Matcher m = p.matcher(s);
        while (m.find()) {
            c++;
            if (c >= cap)
                break;
        }
        return c;
    }

    private static String stripAfterFirstMatch(String text, Pattern marker) {
        if (text == null)
            return null;
        Matcher m = marker.matcher(text);
        if (m.find()) {
            return text.substring(0, m.start());
        }
        return text;
    }

    private static void record(String stage, String reason, int lines, int logLines, int stackLines, String sid) {
        try {
            TraceStore.append("vector.poison.events", String.format(Locale.ROOT,
                    "%s:%s(lines=%d,log=%d,stack=%d,sid=%s)",
                    (stage == null ? "unknown" : stage),
                    (reason == null ? "" : reason),
                    lines, logLines, stackLines, sid == null ? "" : sid));
        } catch (Exception ignore) {
        }
        if (log.isDebugEnabled()) {
            log.debug("[VectorPoisonGuard] stage={} reason={} lines={} logLines={} stackLines={} sid={}",
                    stage, reason, lines, logLines, stackLines, sid);
        }
    }
}

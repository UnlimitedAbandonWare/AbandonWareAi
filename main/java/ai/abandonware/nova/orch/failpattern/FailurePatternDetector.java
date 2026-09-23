package ai.abandonware.nova.orch.failpattern;

import com.example.lms.search.TraceStore;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detect failure patterns from log messages.
 *
 * <p>Matches are based on strings already emitted by:
 * <ul>
 *     <li>HybridWebSearchProvider: [Naver-Trace] Hard Timeout ...</li>
 *     <li>NightmareBreaker: [NightmareBreaker] OPEN key=...</li>
 *     <li>QueryDisambiguationService: [Disambig] ... falling back.</li>
 * </ul>
 */
public final class FailurePatternDetector {

    private static final Pattern NAVER_TRACE_TIMEOUT =
            Pattern.compile("\\[Naver-Trace\\]\\s+Hard\\s+Timeout", Pattern.CASE_INSENSITIVE);

    private static final Pattern DISAMBIG_FALLBACK =
            Pattern.compile("\\[Disambig\\].*falling\\s+back", Pattern.CASE_INSENSITIVE);

    private static final Pattern RAG_STARVATION =
            Pattern.compile("\\[AWX2AF2\\]\\[rag\\]\\[starvation\\].*zero_result_reason=([a-zA-Z0-9_.-]+)",
                    Pattern.CASE_INSENSITIVE);

    // Example: "[NightmareBreaker] OPEN key=WEBSEARCH_BRAVE kind=... ..."
    private static final Pattern NIGHTMAREBREAKER_OPEN =
            Pattern.compile("\\[NightmareBreaker\\]\\s+OPEN\\s+key=([^\\s]+)", Pattern.CASE_INSENSITIVE);

    // Example: "NightmareBreaker OPEN for Brave, skipping ..."
    private static final Pattern HYBRID_OPEN =
            Pattern.compile("NightmareBreaker\\s+OPEN\\s+for\\s+([^,]+)", Pattern.CASE_INSENSITIVE);

    // Conservative generic circuit-open signal (fallback)
    private static final Pattern GENERIC_CIRCUIT_OPEN =
            Pattern.compile("\\b[Cc]ircuit\\s*breaker\\b.*\\bOPEN\\b|\\bOPEN\\b.*\\b[Cc]ircuit\\b");

    public FailurePatternMatch detect(String loggerName, String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        if (NAVER_TRACE_TIMEOUT.matcher(message).find()) {
            return detected(new FailurePatternMatch(FailurePatternKind.NAVER_TRACE_TIMEOUT, "web", "naver-trace"),
                    message);
        }

        if (DISAMBIG_FALLBACK.matcher(message).find()) {
            return detected(new FailurePatternMatch(FailurePatternKind.DISAMBIG_FALLBACK, "disambig", "fallback"),
                    message);
        }

        Matcher m = RAG_STARVATION.matcher(message);
        if (m.find()) {
            String reason = safeLower(m.group(1));
            return detected(new FailurePatternMatch(kindForSearchRecovery(reason), inferSearchRecoverySource(message), reason),
                    message);
        }

        m = NIGHTMAREBREAKER_OPEN.matcher(message);
        if (m.find()) {
            String key = safeLower(m.group(1));
            return detected(new FailurePatternMatch(FailurePatternKind.CIRCUIT_OPEN, inferSourceFromKey(key), key),
                    message);
        }

        m = HYBRID_OPEN.matcher(message);
        if (m.find()) {
            String who = safeLower(m.group(1));
            return detected(new FailurePatternMatch(FailurePatternKind.CIRCUIT_OPEN, "web", who), message);
        }

        if (GENERIC_CIRCUIT_OPEN.matcher(message).find()) {
            // Avoid high-cardinality parsing here; keep it coarse.
            return detected(new FailurePatternMatch(FailurePatternKind.CIRCUIT_OPEN, "web", "generic"), message);
        }

        // Future: add more patterns as needed.
        traceDetector(null, message);
        return null;
    }

    private static FailurePatternMatch detected(FailurePatternMatch match, String message) {
        traceDetector(match, message);
        return match;
    }

    private static void traceDetector(FailurePatternMatch match, String message) {
        try {
            boolean activated = match != null;
            double jbScore = activated ? jbScore(match.kind()) : 0.0d;
            double cbScore = activated ? cbScore(match.kind()) : 0.0d;
            TraceStore.put("cfvm.jb.score", jbScore);
            TraceStore.put("cfvm.cb.score", cbScore);
            TraceStore.put("cfvm.lissajous.pattern", lissajousPattern(activated, jbScore, cbScore));
            TraceStore.put("cfvm.detector.activated", activated);
            TraceStore.put("cfvm.detector.messageLength", message == null ? 0 : message.length());
            if (activated) {
                TraceStore.put("cfvm.detector.kind", match.kind().name());
                TraceStore.put("cfvm.detector.source", safeLower(match.source()));
            }
        } catch (RuntimeException ex) {
            FailurePatternTrace.traceSkipped("failurePatternDetector.trace", ex);
        }
    }

    private static double jbScore(FailurePatternKind kind) {
        if (kind == null) {
            return 0.0d;
        }
        return switch (kind) {
            case NAVER_TRACE_TIMEOUT -> 0.85d;
            case CIRCUIT_OPEN -> 0.75d;
            case DISAMBIG_FALLBACK -> 0.45d;
            case SEARCH_AFTER_FILTER_STARVATION, SEARCH_ZERO_RESULT, WEB_STARVATION -> 0.35d;
            case EVIDENCE_INSUFFICIENT -> 0.50d;
        };
    }

    private static double cbScore(FailurePatternKind kind) {
        if (kind == null) {
            return 0.0d;
        }
        return switch (kind) {
            case SEARCH_AFTER_FILTER_STARVATION, WEB_STARVATION -> 0.90d;
            case SEARCH_ZERO_RESULT -> 0.85d;
            case EVIDENCE_INSUFFICIENT -> 0.80d;
            case CIRCUIT_OPEN -> 0.75d;
            case DISAMBIG_FALLBACK -> 0.65d;
            case NAVER_TRACE_TIMEOUT -> 0.55d;
        };
    }

    private static String lissajousPattern(boolean activated, double jbScore, double cbScore) {
        if (!activated) {
            return "none";
        }
        if (jbScore >= 0.70d && cbScore >= 0.70d) {
            return "coupled_failure";
        }
        if (cbScore >= 0.70d) {
            return "chain_breakdown";
        }
        if (jbScore >= 0.70d) {
            return "job_behavior";
        }
        return "low_signal";
    }

    private static String inferSourceFromKey(String keyLower) {
        if (keyLower == null) {
            return "web";
        }
        String k = keyLower;
        if (k.contains("vector") || k.contains("rag")) {
            return "vector";
        }
        if (k.contains("kg") || k.contains("graph")) {
            return "kg";
        }
        if (k.contains("disambig")) {
            return "disambig";
        }

        // QueryTransformer는 다른 LLM 보조 경로와 분리해서 쿨다운 전염을 막는다.
        if (k.contains("query-transformer") || k.contains("query_transformer") || k.contains("querytransformer")
                || (k.contains("query") && k.contains("transformer"))) {
            return "qtx";
        }

        // LLM/Chat 관련 키 분류
        if (k.contains("llm") || k.contains("chat") || k.contains("draft")
                || k.contains("completion") || k.contains("model")) {
            return "llm";
        }

        return "web";
    }

    private static FailurePatternKind kindForSearchRecovery(String reason) {
        String r = safeLower(reason);
        if (r.contains("after_filter")) {
            return FailurePatternKind.SEARCH_AFTER_FILTER_STARVATION;
        }
        if (r.contains("citation")
                || r.contains("evidence")
                || r.contains("authority")
                || r.contains("coverage")) {
            return FailurePatternKind.EVIDENCE_INSUFFICIENT;
        }
        return FailurePatternKind.SEARCH_ZERO_RESULT;
    }

    private static String inferSearchRecoverySource(String message) {
        String m = safeLower(message).replace('-', '_');
        if (hasProviderField(m, "naver") || m.contains("web.naver.")) {
            return "naver";
        }
        if (hasProviderField(m, "brave") || m.contains("web.brave.")) {
            return "brave";
        }
        if (hasProviderField(m, "serpapi")
                || hasProviderField(m, "serp_api")
                || m.contains("web.serpapi.")
                || m.contains("web.serp_api.")) {
            return "serpapi";
        }
        if (hasProviderField(m, "tavily") || m.contains("web.tavily.")) {
            return "tavily";
        }
        return "rag";
    }

    private static boolean hasProviderField(String message, String provider) {
        return message.contains("provider=" + provider)
                || message.contains("source=" + provider)
                || message.contains("retriever=" + provider)
                || message.contains("retriever_used=" + provider)
                || message.contains("web_provider=" + provider);
    }

    private static String safeLower(String s) {
        if (s == null) {
            return "unknown";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return "unknown";
        }
        return t.toLowerCase(Locale.ROOT);
    }
}

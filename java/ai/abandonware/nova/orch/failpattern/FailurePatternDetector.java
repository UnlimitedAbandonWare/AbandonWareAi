package ai.abandonware.nova.orch.failpattern;

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
            return new FailurePatternMatch(FailurePatternKind.NAVER_TRACE_TIMEOUT, "web", "naver-trace");
        }

        if (DISAMBIG_FALLBACK.matcher(message).find()) {
            return new FailurePatternMatch(FailurePatternKind.DISAMBIG_FALLBACK, "disambig", "fallback");
        }

        Matcher m = NIGHTMAREBREAKER_OPEN.matcher(message);
        if (m.find()) {
            String key = safeLower(m.group(1));
            return new FailurePatternMatch(FailurePatternKind.CIRCUIT_OPEN, inferSourceFromKey(key), key);
        }

        m = HYBRID_OPEN.matcher(message);
        if (m.find()) {
            String who = safeLower(m.group(1));
            return new FailurePatternMatch(FailurePatternKind.CIRCUIT_OPEN, "web", who);
        }

        if (GENERIC_CIRCUIT_OPEN.matcher(message).find()) {
            // Avoid high-cardinality parsing here; keep it coarse.
            return new FailurePatternMatch(FailurePatternKind.CIRCUIT_OPEN, "web", "generic");
        }

        // Future: add more patterns as needed.
        return null;
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

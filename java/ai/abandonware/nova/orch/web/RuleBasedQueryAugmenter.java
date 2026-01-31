package ai.abandonware.nova.orch.web;

import ai.abandonware.nova.config.NovaWebFailSoftProperties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic query canonicalizer/augmenter that works even when the LLM helper
 * (QueryTransformer / planner) is breaker-open.
 *
 * <p>Goals:
 * <ul>
 *   <li>Fix common aliases/typos (잼미나이 → Gemini)</li>
 *   <li>Infer simple intent (TECH_API vs FINANCE vs GENERAL)</li>
 *   <li>For TECH_API, add a tiny number of English keywords to keep recall on official docs</li>
 *   <li>For FINANCE and selected GENERAL (company/entity lookup), add tiny deterministic anchors</li>
 *   <li>Optionally append negative tokens to reduce finance spam (TECH_API only)</li>
 * </ul>
 */
public class RuleBasedQueryAugmenter {

    public enum Intent { TECH_API, FINANCE, GENERAL }

    public record Augment(String canonical,
                          List<String> queries,
                          Set<String> negativeTerms,
                          Intent intent) {
    }

    private final NovaWebFailSoftProperties props;

    public RuleBasedQueryAugmenter(NovaWebFailSoftProperties props) {
        this.props = Objects.requireNonNull(props);
    }

    /**
     * Backward/forward compatible overload. Some call-sites may pass a context object (often null).
     * This implementation is deterministic and currently ignores the context.
     */
    public Augment augment(String rawQuery, Object ctxOrNull) {
        return augment(rawQuery);
    }

    public Augment augment(String rawQuery) {
        String canonical = canonicalize(rawQuery);
        Intent intent = inferIntent(canonical);

        LinkedHashSet<String> base = new LinkedHashSet<>();
        if (!canonical.isBlank()) {
            base.add(canonical);
        }

        if (intent == Intent.TECH_API) {
            base.addAll(techAugmentations(canonical));
        } else if (intent == Intent.FINANCE) {
            base.addAll(financeAugmentations(canonical));
        } else {
            base.addAll(generalAugmentations(canonical));
        }

        // Operator-tunable rescue query templates (OFFICIAL/DOCS starvation).  Keep it small.
        base.addAll(rescueFromConfig(canonical));

        Set<String> negatives = new LinkedHashSet<>();
        if (intent == Intent.TECH_API) {
            negatives.addAll(props.getTechSpamKeywords());
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String q : base) {
            if (q == null || q.isBlank()) continue;
            out.add(applyNegatives(q, negatives));
        }

        return new Augment(canonical, new ArrayList<>(out), negatives, intent);
    }

    private String canonicalize(String q) {
        if (q == null) return "";
        String s = q.trim();

        // Strip internal autolearn prefixes/tags to keep web search queries clean.
        s = s.replaceFirst("^(?i)\\s*(내부\\s*자동학습|uaw)\\s*:\\s*", "");
        s = s.replaceFirst("^\\(\\s*(?i:curiosity|gap|retrain|autolearn|uaw)\\s*\\)\\s*", "");
        s = s.replaceFirst("^\\[\\s*(?i:curiosity|gap|retrain|autolearn|uaw)\\s*\\]\\s*", "");

        for (Map.Entry<String, String> e : props.getAliasMap().entrySet()) {
            String from = e.getKey();
            String to = e.getValue();
            if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
                s = s.replace(from, to);
            }
        }
        // normalize whitespace
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private Intent inferIntent(String canonical) {
        String lower = canonical == null ? "" : canonical.toLowerCase(Locale.ROOT);

        boolean hasAiBrand =
                lower.contains("gemini") ||
                        lower.contains("chatgpt") ||
                        lower.contains("openai") ||
                        lower.contains("anthropic") ||
                        lower.contains("claude") ||
                        lower.contains("gpt");

        boolean hasTechSignals =
                lower.contains(" api") || lower.startsWith("api ") ||
                        lower.contains("sdk") ||
                        lower.contains("token") ||
                        lower.contains("billing") ||
                        lower.contains("pricing") ||
                        lower.contains("quota") ||
                        lower.contains("rate limit") ||
                        lower.contains("요금") ||
                        lower.contains("과금") ||
                        lower.contains("쿼터") ||
                        lower.contains("토큰");

        boolean hasFinanceSignals =
                containsAny(lower, props.getTechSpamKeywords()) ||
                        lower.contains("대부") || lower.contains("금융") || lower.contains("은행");

        // If it smells like finance and doesn't name an AI product/vendor, treat as FINANCE.
        if (hasFinanceSignals && !hasAiBrand) return Intent.FINANCE;

        if (hasAiBrand && hasTechSignals) return Intent.TECH_API;
        if (hasAiBrand && (lower.contains("한도") || lower.contains("무료"))) return Intent.TECH_API;

        return Intent.GENERAL;
    }

    

    /**
     * Additional rescue queries controlled by configuration.
     *
     * <p>Entries may contain <code>{canonical}</code> or <code>${canonical}</code> placeholder.</p>
     * <p>We intentionally cap the expansion to avoid query explosion.</p>
     */
    private List<String> rescueFromConfig(String canonical) {
        List<String> out = new ArrayList<>();
        if (canonical == null || canonical.isBlank()) return out;

        List<String> templates = props.getOfficialDocsRescueQueries();
        if (templates == null || templates.isEmpty()) return out;

        String entity = extractLeadingEntity(canonical);

        int limit = 4;
        for (String t : templates) {
            if (t == null || t.isBlank()) continue;
            String q = t.replace("{canonical}", canonical).replace("${canonical}", canonical)
                    .replace("{entity}", (entity == null ? "" : entity))
                    .replace("${entity}", (entity == null ? "" : entity))
                    .trim();
            if (q.isBlank()) continue;
            out.add(q);
            if (out.size() >= limit) break;
        }
        return out;
    }

private List<String> techAugmentations(String canonical) {
        String lower = canonical == null ? "" : canonical.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();

        boolean isGemini = lower.contains("gemini");
        if (isGemini) {
            out.add("Gemini API pricing free tier quota");
            out.add("Gemini API billing free tier quota");
            out.add("ai.google.dev gemini api pricing billing quota");
            out.add("Gemini API 무료 할당량 쿼터 한도 요금");
        }

        // If user says "한도", push "quota/rate limit" explicitly.
        if (lower.contains("한도")) {
            out.add(canonical + " quota rate limit");
        }

        // keep it small (avoid query explosion)
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    /**
     * For FINANCE intent, add small "official/regulator" anchors so we can still retrieve
     * useful sources when LLM-based query transform is down.
     */
    private List<String> financeAugmentations(String canonical) {
        String lower = canonical == null ? "" : canonical.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (canonical == null || canonical.isBlank()) {
            return out;
        }

        // Prefer authoritative/official anchors (KR). Keep it small.
        out.add(canonical + " 금융감독원");
        out.add(canonical + " 금융위원회");
        out.add(canonical + " 한국은행");

        if (lower.contains("대출")) {
            out.add(canonical + " 대출 금리 조건");
        }
        if (lower.contains("카드")) {
            out.add(canonical + " 카드 연회비 혜택");
        }

        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    /**
     * For GENERAL intent, we only add augmentations when the query smells like a company/entity lookup.
     * This prevents query explosion on arbitrary general questions.
     */
    private List<String> generalAugmentations(String canonical) {
        List<String> out = new ArrayList<>();
        if (canonical == null || canonical.isBlank()) {
            return out;
        }

        // Heuristic: only add GENERAL augmentations when the query smells like a company/entity lookup.
        // (We want to avoid query explosion on arbitrary general questions.)
        boolean looksCompany = canonical.contains("회사") || canonical.contains("기업") || canonical.contains("스타트업")
                || canonical.contains("어떤 회사") || canonical.contains("회사 소개") || canonical.contains("기업 소개");

        boolean looksEntity = canonical.endsWith("뭐야") || canonical.endsWith("뭐냐") || canonical.endsWith("뭔데")
                || canonical.contains("뭐야") || canonical.contains("뭐냐") || canonical.contains("누구")
                || canonical.contains("정체") || canonical.contains("프로필") || canonical.contains("공식");

        if (!(looksCompany || looksEntity)) {
            return out;
        }

        String entity = extractLeadingEntity(canonical);
        if (entity == null || entity.isBlank()) {
            return out;
        }

        if (looksCompany) {
            out.add(entity + " 공식 홈페이지");
            out.add(entity + " 회사 소개");
            out.add(entity + " 어떤 회사");
            out.add(entity + " 로켓펀치");
            out.add(entity + " 더브이씨");
            out.add(entity + " 잡플래닛");
        } else {
            // Lightweight entity/profile lookups (often helps OFFICIAL/DOCS recovery for names).
            out.add(entity + " 공식");
            out.add(entity + " 공식 사이트");
            out.add(entity + " 프로필");
            out.add(entity + " 공식 유튜브");
            out.add(entity + " 트위터");
            out.add(entity + " 인스타");
        }

        return out.size() > 6 ? out.subList(0, 6) : out;
    }

    /**
     * Extract a best-effort leading named entity token from a Korean/English query.
     * This is intentionally simple/deterministic.
     */
    private static String extractLeadingEntity(String canonical) {
        if (canonical == null) {
            return null;
        }
        String s = canonical.trim();
        if (s.isBlank()) {
            return null;
        }

        // Drop leading quotes/brackets.
        s = s.replaceAll("^[\\\"'`\\[\\(]+", "");
        if (s.isBlank()) {
            return null;
        }

        String[] parts = s.split("\\s+");
        if (parts.length == 0) {
            return null;
        }

        String token = parts[0];
        token = token.replaceAll("[\\?\\!\\,\\.\\:\\;\\)\\]\\\"']+$", "");

        // Strip common Korean particles/suffixes for proper nouns.
        token = token.replaceAll("(?:이라는|라면|란|은|는|이|가|을|를|의)$", "");
        token = token.trim();

        if (token.length() < 2) {
            return null;
        }
        if (token.startsWith("http")) {
            return null;
        }
        return token;
    }

    private static String applyNegatives(String query, Set<String> negatives) {
        if (query == null) return "";
        if (negatives == null || negatives.isEmpty()) return query;

        StringBuilder sb = new StringBuilder(query);
        for (String neg : negatives) {
            if (neg == null || neg.isBlank()) continue;
            if (query.contains("-" + neg)) continue;
            sb.append(" -").append(neg);
        }
        return sb.toString();
    }

    public static boolean containsAny(String haystackLower, Collection<String> needles) {
        if (haystackLower == null || haystackLower.isBlank()) return false;
        if (needles == null || needles.isEmpty()) return false;
        for (String n : needles) {
            if (n == null || n.isBlank()) continue;
            if (haystackLower.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}

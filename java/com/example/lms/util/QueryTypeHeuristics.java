package com.example.lms.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Lightweight query-type heuristics used by fail-soft guards.
 *
 * <p>Goal: keep logic deterministic and cheap so it can be used inside hot paths
 * (guards, retries, aux fallbacks) without depending on heavyweight NLP models.
 */
public final class QueryTypeHeuristics {

    private QueryTypeHeuristics() {
    }

    private static final Pattern TOKEN_SPLIT = Pattern.compile("\\s+|[\\u3000-\\u303F\\p{Punct}]");
    private static final Pattern HAS_HANGUL_OR_LETTER = Pattern.compile(".*[\\p{IsHangul}\\p{L}].*");
    private static final Pattern DIGITS_ONLY = Pattern.compile("\\d+");

    // Definitional queries often benefit from forcing regeneration rather than returning "sources-only".
    private static final Pattern DEFINITONAL = Pattern.compile(
            "(?i).*(뭐야|뭐냐|뭐지|뭔지|뭔데|누구야|누구냐|누구지|무엇|정의|뜻|의미|설명|소개|개념|란\\b|이란\\b|"
                    + "what\\s+is|who\\s+is|meaning\\s+of|define\\b|definition\\s+of|explain\\b|overview\\s+of|tell\\s+me\\s+about).*");

    // Phrases that commonly indicate an entity-focused query.
    private static final Pattern ENTITY_HINT = Pattern.compile(
            "(?i).*(프로필|약력|경력|이력|소속|나이|키|위키|wiki|bio|profile|"
                    + "공식\\s*홈페이지|홈페이지|official\\s*(site|website)|website|homepage).*");

    // Avoid misclassifying long "how-to/recommendation" prompts as entity queries.
    private static final Pattern TASKY = Pattern.compile(
            "(?i).*(추천|비교|방법|하는법|어떻게|how\\s+to|guide|tutorial).*");

    /**
     * TraceStore key {@code web.failsoft.starvationFallback.trigger} is stringly-typed; make comparison robust.
     */
    public static boolean isBelowMinCitationsTrigger(Object trigger) {
        if (trigger == null) {
            return false;
        }
        String s = String.valueOf(trigger).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return false;
        }
        String u = s.toUpperCase(Locale.ROOT);
        return "BELOW_MIN_CITATIONS".equalsIgnoreCase(s) || u.contains("BELOW_MIN_CITATIONS");
    }

    public static boolean isDefinitional(String query) {
        if (query == null) {
            return false;
        }
        String s = query.trim();
        if (s.isEmpty()) {
            return false;
        }
        return DEFINITONAL.matcher(s).matches();
    }

    /**
     * Heuristic: determine whether a prompt is likely "entity-like" (person/org/title/product name).
     * This is used only for fail-soft decisions, not as a security boundary.
     */
    public static boolean looksLikeEntityQuery(String query) {
        if (query == null) {
            return false;
        }
        String s = query.trim();
        if (s.isEmpty()) {
            return false;
        }

        // definitional forms are treated as entity-like for our purposes.
        if (isDefinitional(s)) {
            return true;
        }

        // explicit keywords => almost certainly entity-focused
        if (ENTITY_HINT.matcher(s).matches()) {
            return true;
        }

        String lower = s.toLowerCase(Locale.ROOT);

        // long + tasky => likely not a simple entity lookup
        if (s.length() >= 80 && TASKY.matcher(lower).matches()) {
            return false;
        }

        List<String> tokens = tokenize(s);
        if (tokens.isEmpty()) {
            return false;
        }

        // single token and not numeric => likely a named entity
        if (tokens.size() == 1) {
            String t = tokens.get(0);
            return looksLikeNameToken(t);
        }

        // short prompt with few tokens => often an entity/name query
        if (tokens.size() <= 3 && s.length() <= 40) {
            int good = 0;
            for (String t : tokens) {
                if (looksLikeNameToken(t)) {
                    good++;
                }
            }
            return good >= 1;
        }

        // "Name + descriptor" (e.g., "<name> 공식 홈페이지", "<name> 프로필")
        if (tokens.size() <= 8) {
            String t0 = tokens.get(0);
            String t1 = tokens.get(1);

            if (looksLikeNameToken(t0) && looksLikeNameToken(t1)) {
                // avoid obvious generic question prefixes
                if (!(lower.startsWith("어떻게") || lower.startsWith("방법") || lower.startsWith("추천"))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static List<String> tokenize(String query) {
        String cleaned = TOKEN_SPLIT.matcher(query).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        String[] raw = cleaned.split("\\s+");
        List<String> out = new ArrayList<>(raw.length);
        for (String t : raw) {
            if (t == null) {
                continue;
            }
            String tt = t.trim();
            if (tt.isEmpty()) {
                continue;
            }
            out.add(tt);
        }
        return out;
    }

    private static boolean looksLikeNameToken(String raw) {
        if (raw == null) {
            return false;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return false;
        }
        if (t.length() < 2 || t.length() > 32) {
            return false;
        }
        if (DIGITS_ONLY.matcher(t).matches()) {
            return false;
        }

        String lower = t.toLowerCase(Locale.ROOT);
        if (isStopToken(lower)) {
            return false;
        }

        // year-ish / number-ish tokens like "2026년에" should not count as a name token
        if (t.matches(".*\\d.*") && !t.matches(".*[a-zA-Z].*")) {
            // contains digits but no latin letters => likely a year/number token
            return false;
        }

        return containsHangulOrLetter(t);
    }

    private static boolean isStopToken(String tokenLower) {
        if (tokenLower == null) {
            return true;
        }
        // minimal stop list: we only use this to avoid selecting obvious non-entity tokens
        return tokenLower.equals("추천")
                || tokenLower.equals("후기")
                || tokenLower.equals("리뷰")
                || tokenLower.equals("정리")
                || tokenLower.equals("방법")
                || tokenLower.equals("하는법")
                || tokenLower.equals("가격")
                || tokenLower.equals("정보")
                || tokenLower.equals("공식")
                || tokenLower.equals("홈페이지")
                || tokenLower.equals("사이트")
                || tokenLower.equals("official")
                || tokenLower.equals("site")
                || tokenLower.equals("website")
                || tokenLower.equals("homepage");
    }

    private static boolean containsHangulOrLetter(String token) {
        if (token == null) {
            return false;
        }
        return HAS_HANGUL_OR_LETTER.matcher(token).matches();
    }
}

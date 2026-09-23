package com.example.lms.search.provider;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Pure query transformations shared by bounded and legacy hybrid search routes. */
final class HybridSearchQueryPolicy {
    private static final List<String> ADVANCED_OPERATORS =
            List.of("site", "inurl", "intitle", "filetype", "ext");

    private HybridSearchQueryPolicy() {
    }

    static boolean containsHangul(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
            if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return true;
            }
        }
        return false;
    }

    static String extractKeywords(String query) {
        if (query == null) {
            return null;
        }
        // Best-effort removal of question/filler terms before fallback query building.
        String s = query;
        s = s.replaceAll(
                "(?:\uB204\uAD6C\uC57C|\uBB50\uC57C|\uBB34\uC5C7\uC774\uC57C|\uC54C\uB824\uC918|\uB9D0\uD574\uC918|\uAC80\uC0C9\uD574(?:\uC918)?|\uCC3E\uC544\uC918|\uC124\uBA85\uD574\uC918)",
                "");
        // Normalize common honorific forms.
        s = s.replaceAll("\uAD50\uC218\uB2D8", "\uAD50\uC218")
                .replaceAll("\uC120\uC0DD\uB2D8", "\uC120\uC0DD")
                .replaceAll("\uC758\uC0AC\uC120\uC0DD\uB2D8", "\uC758\uC0AC");
        return s.trim();
    }

    static String convertToEnglishSearchTerm(String query) {
        if (!StringUtils.hasText(query))
            return query;
        String normalized = query.toLowerCase().replaceAll("\\s+", "");

        // Do not append a technical suffix for game/character intent queries.
        boolean isGameIntent = normalized.contains("\uC6D0\uC2E0")
                || normalized.contains("genshin")
                || normalized.contains("\uCE90\uB9AD\uD130")
                || normalized.contains("\uC870\uD569")
                || normalized.contains("\uD2F0\uC5B4")
                || normalized.contains("\uBE4C\uB4DC");
        if (isGameIntent) {
            return query; // ?먮낯 洹몃?濡?諛섑솚
        }

        // Detect tech-product markers; without them, avoid adding a tech suffix.
        boolean hasTechMarker = normalized
                .matches(".*(galaxy\\s*s\\d{2}|fold|flip|iphone|pixel|snapdragon|exynos|rtx|cpu|gpu|notebook|laptop|\uAC24\uB7ED\uC2DC|\uC544\uC774\uD3F0).*");

        boolean rumor = hasRumorIntent(normalized);

        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        if (normalized.contains("\uD3F4\uB4DC7") || normalized.contains("zfold7") || normalized.contains("fold7")) {
            return rumor
                    ? "Galaxy Z Fold 7 leak rumors renders"
                    : "Samsung Galaxy Z Fold7 official specs release date price";
        }

        // [KO] Legacy Korean comment was mojibake; behavior is defined by the code below.
        if (rumor) {
            return query + " latest leaks rumors";
        }

        // Expand only when a tech marker and a specs/release/price/review intent are both present.
        if (hasTechMarker
                && normalized.matches(".*(spec|release|price|review|\uC2A4\uD399|\uC0AC\uC591|\uCD9C\uC2DC|\uAC00\uACA9|\uB9AC\uBDF0|\uBE44\uAD50).*")) {
            return query + " official specs release date price review";
        }

        return query;
    }

    private static boolean hasRumorIntent(String normalized) {
        if (normalized == null)
            return false;
        return normalized.contains("\uB8E8\uBA38")
                || normalized.contains("\uC720\uCD9C")
                || normalized.contains("\uB80C\uB354")
                || normalized.contains("leak")
                || normalized.contains("rumor")
                || normalized.contains("renders");
    }

    // Preserve advanced search operators (site:/inurl:/intitle:/filetype:/ext:) in
    // backup queries.
    // Normalisation like latinOnly must not destroy these operators.
    private static boolean containsAdvancedSearchOperators(String q) {
        if (q == null || q.isBlank()) {
            return false;
        }
        String s = q.toLowerCase(Locale.ROOT);
        return ADVANCED_OPERATORS.stream().anyMatch(operator -> s.contains(operator + ":"));
    }

    private static boolean isAdvancedSearchOperatorToken(String token) {
        if (token == null) {
            return false;
        }
        String t = token.trim();
        if (t.isEmpty()) {
            return false;
        }
        int idx = t.indexOf(':');
        if (idx <= 0 || idx >= t.length() - 1) {
            return false;
        }
        String op = t.substring(0, idx).toLowerCase(Locale.ROOT);
        return ADVANCED_OPERATORS.contains(op);
    }

    private static String trimEdgePunct(String token) {
        if (token == null) {
            return "";
        }
        String t = token.trim();
        // Strip wrapping quotes/brackets often attached in user input.
        while (!t.isEmpty()) {
            char c = t.charAt(0);
            if (c == '"' || c == '`' || c == '(' || c == '[' || c == '{' || c == '<') {
                t = t.substring(1).trim();
                continue;
            }
            break;
        }
        while (!t.isEmpty()) {
            char c = t.charAt(t.length() - 1);
            if (c == '"' || c == '`' || c == ')' || c == ']' || c == '}' || c == '>'
                    || c == ',' || c == ';' || c == '.') {
                t = t.substring(0, t.length() - 1).trim();
                continue;
            }
            break;
        }
        return t;
    }

    private static String buildOperatorPreservedBackupQuery(String q) {
        if (q == null) {
            return "";
        }
        String s = q.trim();
        if (s.isBlank()) {
            return "";
        }

        String[] toks = s.split("\\s+");
        LinkedHashSet<String> ops = new LinkedHashSet<>();
        List<String> rest = new ArrayList<>();

        for (String tok : toks) {
            if (tok == null) {
                continue;
            }
            String cleaned = trimEdgePunct(tok);
            if (isAdvancedSearchOperatorToken(cleaned)) {
                ops.add(cleaned);
            } else {
                rest.add(tok);
            }
        }

        if (ops.isEmpty()) {
            return "";
        }

        // Keep the operator tokens verbatim, but dedupe and shorten the rest.
        String restJoined = String.join(" ", rest).trim();
        String restKeywords = extractKeywords(restJoined);
        if (restKeywords == null) {
            restKeywords = "";
        }
        restKeywords = restKeywords.replaceAll("\\s+", " ").trim();

        LinkedHashSet<String> restUniq = new LinkedHashSet<>();
        LinkedHashSet<String> seenLower = new LinkedHashSet<>();
        if (!restKeywords.isBlank()) {
            for (String tok : restKeywords.split("\\s+")) {
                if (tok == null) {
                    continue;
                }
                String t = tok.trim();
                if (t.isBlank()) {
                    continue;
                }
                String key = t.toLowerCase(Locale.ROOT);
                if (seenLower.contains(key)) {
                    continue;
                }
                seenLower.add(key);
                restUniq.add(t);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String op : ops) {
            if (op == null || op.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(op.trim());
        }

        int restLimit = 4;
        int used = 0;
        for (String t : restUniq) {
            if (used >= restLimit) {
                break;
            }
            if (t == null || t.isBlank()) {
                continue;
            }
            sb.append(' ').append(t.trim());
            used++;
        }

        return sb.toString().trim();
    }

    static String buildBackupQuery(String originalQuery, boolean wantBraveFriendly) {
        if (originalQuery == null)
            return "";
        String q = originalQuery.trim();
        if (q.isBlank())
            return "";

        final boolean hasOps = containsAdvancedSearchOperators(q);
        final String operatorPreserved = hasOps ? buildOperatorPreservedBackupQuery(q) : "";

        String keywords = extractKeywords(q);
        String english = convertToEnglishSearchTerm(q);

        String latinOnly = hasOps ? "" : q.replaceAll("[^A-Za-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();

        // Avoid degenerate backup queries (e.g., year-only like "2026") that tend to
        // produce spammy or irrelevant results.
        if (keywords != null && keywords.trim().matches("^\\d+$")) {
            keywords = "";
        }
        if (english != null && english.trim().matches("^\\d+$")) {
            english = "";
        }
        if (!latinOnly.isBlank() && latinOnly.trim().matches("^\\d+$")) {
            latinOnly = "";
        }

        if (hasOps && StringUtils.hasText(operatorPreserved)
                && !operatorPreserved.equalsIgnoreCase(q)) {
            return operatorPreserved;
        }
        if (wantBraveFriendly) {
            if (!latinOnly.isBlank() && !latinOnly.equalsIgnoreCase(q))
                return latinOnly;
            if (english != null && !english.isBlank() && !english.equalsIgnoreCase(q))
                return english;
            if (keywords != null && !keywords.isBlank() && !keywords.equalsIgnoreCase(q))
                return keywords;
        } else {
            if (keywords != null && !keywords.isBlank() && !keywords.equalsIgnoreCase(q))
                return keywords;
            if (english != null && !english.isBlank() && !english.equalsIgnoreCase(q))
                return english;
            if (!latinOnly.isBlank() && !latinOnly.equalsIgnoreCase(q))
                return latinOnly;
        }

        // Last resort: shorten overly long queries.
        String[] toks = q.replaceAll("\\s+", " ").trim().split(" ");
        if (toks.length > 6) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0)
                    sb.append(' ');
                sb.append(toks[i]);
            }
            String shortened = sb.toString().trim();
            if (shortened.matches("^\\d+$")) {
                return "";
            }
            return shortened;
        }
        return "";
    }

}

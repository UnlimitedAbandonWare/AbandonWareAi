
package com.example.lms.service.rag.fusion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;





public class WeightedRrfFuser<T extends WeightedRrfFuser.Result> {

    /** Default k constant used in the reciprocal rank formula. */
    private final int k;
    /** Internal list of (result, weight) pairs. */
    private final List<Entry<T>> entries = new ArrayList<>();

    
    public WeightedRrfFuser() {
        this(60);
    }

    
    public WeightedRrfFuser(int k) {
        this.k = Math.max(1, k);
    }

    /**
     * Add a result along with its associated weight.  Results are stored in
     * the order received; this position determines their rank in the RRF
     * computation.  Weights should be positive but no validation is
     * performed.
     *
     * @param result the search result
     * @param weight the per‑source weight for this result
     */
    public void add(T result, double weight) {
        if (result == null) return;
        entries.add(new Entry<>(result, weight));
    }

    /**
     * Fuse the accumulated results into a single ranking using the weighted
     * RRF formula.  When multiple results share the same content they will
     * remain distinct in the output; callers should deduplicate upstream if
     * necessary.
     *
     * @return a list of results sorted in decreasing fused score
     */
    public List<T> fuse() {
        // Optional canonicalize & dedup (toggle via -Dfusion.url.canonicalize=true)
        if (Boolean.getBoolean("fusion.url.canonicalize")) {
            java.util.Map<String, Object> seen = new java.util.LinkedHashMap<>();
            java.util.List tmp = new java.util.ArrayList(results);
            java.util.List deduped = new java.util.ArrayList();
            for (Object r : tmp) {
                try {
                    String url = (String) r.getClass().getMethod("getSource").invoke(r);
                    String key = UrlCanonicalizer.key(url);
                    if (!seen.containsKey(key)) {
                        seen.put(key, Boolean.TRUE);
                        deduped.add(r);
                    }
                } catch (Exception ignore) {
                    deduped.add(r);
                }
            }
            results = deduped;
        }

        // Compute RRF scores.  The rank is determined by the order in the
        // internal list; the first added result has rank 1.
        List<Score<T>> scores = new ArrayList<>();
        int index = 0;
        for (Entry<T> e : entries) {
            index++;
            double score = e.weight / (k + index);
            scores.add(new Score<>(e.result, score));
        }
        // Sort by descending score and then by insertion order for stability
        return scores.stream()
                .sorted(Comparator.comparingDouble((Score<T> s) -> s.score()).reversed())
                .map(Score::result)
                .collect(Collectors.toList());
    }

    
    public static double weight(Locale locale, Result r) {
        if (r == null || locale == null) return 0.0;
        double localeScore = 0.0;
        // Country code top level domain match
        String ccTld = locale.getCountry();
        if (!ccTld.isBlank() && r.domain != null && r.domain.toLowerCase(Locale.ROOT).endsWith("." + ccTld.toLowerCase(Locale.ROOT))) {
            localeScore = 1.0;
        }
        double languageScore = 0.0;
        // Very coarse language detection: presence of Korean characters when locale is Korean
        if ("ko".equalsIgnoreCase(locale.getLanguage())) {
            if (containsKorean(r.title) || containsKorean(r.snippet)) {
                languageScore = 1.0;
            }
        } else if ("de".equalsIgnoreCase(locale.getLanguage()) || "en".equalsIgnoreCase(locale.getLanguage())) {
            // For German or English locales, treat Latin script as a match
            if (containsEnglish(r.title) || containsEnglish(r.snippet)) {
                languageScore = 1.0;
            }
        }
        double authorityScore = 0.0;
        if (r.domain != null && r.domain.toLowerCase(Locale.ROOT).contains("dw.com")) {
            authorityScore = 1.0;
        }
        double recencyScore = 0.0;
        if (r.publishedAt != null) {
            // Consider documents within the last year as recent
            Instant now = Instant.now();
            Instant oneYearAgo = now.minusSeconds(365L * 24L * 3600L);
            if (r.publishedAt.isAfter(oneYearAgo)) {
                recencyScore = 1.0;
            }
        }
        return 0.40 * localeScore + 0.25 * languageScore + 0.20 * authorityScore + 0.15 * recencyScore;
    }

    
    public static record Result(String domain, String title, String snippet, Instant publishedAt) {
        /**
         * Construct a result with no publication timestamp.  Convenience
         * overload for tests that do not specify recency.
         */
        public Result(String domain, String title, String snippet) {
            this(domain, title, snippet, null);
        }
    }

    /** Internal entry pairing a result with its weight. */
    private static class Entry<T extends Result> {
        final T result;
        final double weight;
        Entry(T result, double weight) {
            this.result = result;
            this.weight = weight;
        }
    }

    /**
     * Holder for a scored result.  Used internally when fusing the list of
     * results to preserve both the computed score and the original result.
     */
    private static class Score<T extends Result> {
        final T result;
        final double score;
        Score(T result, double score) {
            this.result = result;
            this.score = score;
        }
        public T result() { return result; }
        public double score() { return score; }
    }

    // ------------------------------------------------------------------------
    // Helper functions used by weight(Locale, Result)

    private static boolean containsKorean(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsEnglish(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    // Added: minimal URL canonicalizer and deduplicator
    static final class UrlCanonicalizer {
        static String key(String url) {
            if (url == null) return "";
            String u = url.trim().toLowerCase();
            u = u.replaceFirst("^https?://", "");
            int q = u.indexOf('?');
            if (q >= 0) u = u.substring(0, q);
            if (u.endsWith("/")) u = u.substring(0, u.length()-1);
            return u;
        }
    }

}
package com.example.lms.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;



/**
 * A naive implementation of {@link SenseDisambiguator} that uses simple
 * keyword, domain and language heuristics to differentiate between local
 * “academy” references and the global “DW Akademie”.  This class does not
 * depend on any external search providers and can operate on a list of
 * arbitrary objects; snippets are inspected via {@link Object#toString()} to
 * extract language and domain cues.  It is intended as a fail-soft
 * shim and can be extended or replaced with a more sophisticated
 * disambiguator.
 */
public class DefaultSenseDisambiguator implements SenseDisambiguator {

    @Override
    public SenseResult candidates(String query, List<?> peekTopK) {
        // Accumulate rough scores for the two supported senses: a local
        // academy located in Korea and the international Deutsche Welle
        // Akademie.  Additional senses can be added in the future.
        double localScore = 0.0;
        double globalScore = 0.0;

        // Inspect the provided web snippet list.  When snippets are
        // unavailable, fall back to using the query string itself.
        List<String> tokens = new ArrayList<>();
        if (peekTopK != null && !peekTopK.isEmpty()) {
            for (Object obj : peekTopK) {
                if (obj == null) continue;
                tokens.add(obj.toString());
            }
        } else if (query != null) {
            tokens.add(query);
        }
        // Evaluate each token and accumulate heuristic scores.  Simple
        // heuristics: presence of Korean characters boosts the local score;
        // Korean domains (.kr) boost the local score; presence of DW or
        // German/English language terms boost the global score.
        for (String s : tokens) {
            if (s == null) continue;
            String lower = s.toLowerCase(Locale.ROOT);
            // Language heuristic: check for Korean characters
            if (containsKorean(lower)) {
                localScore += 1.0;
            }
            // Domain heuristic
            if (lower.contains(".kr")) {
                localScore += 1.0;
            }
            if (lower.contains("dw.com") || lower.contains("deutsche welle") || lower.contains("akademie")) {
                globalScore += 1.0;
            }
            // Use the presence of English words as a weak signal for global
            // content.  This heuristic is intentionally coarse; more
            // sophisticated implementations may use language detection.
            if (containsEnglish(lower)) {
                globalScore += 0.5;
            }
        }
        // Construct candidate senses ordered by their computed score.  When
        // scores are equal or both zero, default to the local academy first.
        Sense local = new Sense("local-academy", localScore, "대전 학원");
        Sense global = new Sense("dw-akademie", globalScore, "DW Akademie");
        List<Sense> senses = new ArrayList<>();
        // Always include both senses so that the caller can inspect the delta
        if (localScore >= globalScore) {
            senses.add(local);
            senses.add(global);
        } else {
            senses.add(global);
            senses.add(local);
        }
        double delta = Math.abs(localScore - globalScore);
        return new SenseResult(senses, delta);
    }

    /**
     * Returns {@code true} if the supplied string contains at least one
     * character in the Hangul syllable range.  This is a simple proxy for
     * language detection used by the disambiguator.
     */
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

    /**
     * Returns {@code true} if the supplied string contains Latin alphabet
     * characters.  Used as a weak indicator of English or German text.
     */
    private static boolean containsEnglish(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                return true;
            }
        }
        return false;
    }
}
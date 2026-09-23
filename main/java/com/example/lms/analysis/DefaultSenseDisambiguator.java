package com.example.lms.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;



/**
 * A conservative implementation of {@link SenseDisambiguator} that uses simple
 * keyword, domain and language heuristics to differentiate generic education
 * organisations from international media or organisation references. This
 * class does not depend on external search providers and inspects snippets via
 * {@link Object#toString()} as a fail-soft shim.
 */
public class DefaultSenseDisambiguator implements SenseDisambiguator {

    @Override
    public SenseResult candidates(String query, List<?> peekTopK) {
        double educationScore = 0.0;
        double internationalScore = 0.0;

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
        for (String s : tokens) {
            if (s == null) continue;
            String lower = s.toLowerCase(Locale.ROOT);
            if (containsKorean(lower)) {
                educationScore += 0.5;
            }
            if (lower.contains(".kr")) {
                educationScore += 1.0;
            }
            if (lower.contains("교육")
                    || lower.contains("학원")
                    || lower.contains("아카데미")
                    || lower.contains("academy")
                    || lower.contains("course")
                    || lower.contains("curriculum")) {
                educationScore += 1.0;
            }
            if (lower.contains("dw.com") || lower.contains("deutsche welle") || lower.contains("akademie")) {
                internationalScore += 1.0;
            }
            if (lower.contains(".de") || lower.contains("international") || lower.contains("global")) {
                internationalScore += 0.5;
            }
        }

        if (educationScore <= 0.0 && internationalScore <= 0.0) {
            return new SenseResult(List.of(), 0.0);
        }

        Sense education = new Sense("education-organization", educationScore, "Education organization");
        Sense international = new Sense("international-organization", internationalScore, "International organization");
        List<Sense> senses = new ArrayList<>();
        if (educationScore >= internationalScore) {
            if (educationScore > 0.0) senses.add(education);
            if (internationalScore > 0.0) senses.add(international);
        } else {
            if (internationalScore > 0.0) senses.add(international);
            if (educationScore > 0.0) senses.add(education);
        }
        double delta = senses.size() > 1
                ? Math.abs(senses.get(0).score() - senses.get(1).score())
                : senses.get(0).score();
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

}

package com.example.lms.search;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.regex.Pattern;



/**
 * Normalises company names by removing legal suffixes and correcting common typos.
 *
 * <p>This helper centralises organisation name cleaning prior to any NER or tokenisation.
 * Legal suffixes such as (주), ㈜, 주식회사, LLC, Inc., Ltd. and Corp. are stripped in a
 * case-insensitive manner.  Frequently observed misspellings are corrected via a
 * static map.  Multiple spaces are collapsed and leading/trailing whitespace
 * removed.</p>
 */
@Component
public class CompanyNormalizer {
    /** Regex for detecting corporate suffixes at word boundaries. */
    private static final Pattern LEGAL_SUFFIX =
            Pattern.compile("(주식회사|\\(주\\)|㈜|유한회사|LLC|Inc\\.?|Ltd\\.?|Corp\\.?)\\b",
                    Pattern.CASE_INSENSITIVE);
    /** Map of common typos to their corrections. */
    private static final Map<String, String> COMMON_TYPO = Map.of(
            "에대해", "에 대해",
            "알와봐", "알아봐"
    );

    /**
     * Normalize the given string by correcting typos and removing legal suffixes.
     *
     * @param q the raw query or company name
     * @return a cleaned string with corporate suffixes and extra whitespace removed
     */
    public String normalize(String q) {
        if (q == null) return null;
        String s = q.trim();
        // correct known typos
        for (var e : COMMON_TYPO.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        // remove corporate suffixes
        s = LEGAL_SUFFIX.matcher(s).replaceAll("");
        // collapse multiple spaces
        s = s.replaceAll("\\s{2,}", " ").trim();
        return s;
    }
}
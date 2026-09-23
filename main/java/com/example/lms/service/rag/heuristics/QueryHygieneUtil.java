package com.example.lms.service.rag.heuristics;

import java.util.ArrayList;
import java.util.List;



/**
 * Utility methods for cleaning user queries and generating variant
 * formulations.  The cleaning rules focus on removing dangling
 * punctuation, stripping corporate suffixes and wrapping the primary
 * entity in quotation marks to preserve phrase semantics.  Variant
 * generation is deliberately simple; it attempts an English
 * transliteration when the canonical Korean name is encountered.
 */
public class QueryHygieneUtil {

    /**
     * Remove leading pipe/commas, trailing OR operators and common
     * corporate suffixes from the input query.  The first remaining
     * token is wrapped in double quotes to stabilise search behaviour.
     *
     * @param q the raw query (may be null)
     * @return a cleaned query string enclosed in quotes
     */
    public String clean(String q) {
        if (q == null) {
            return "\"\"";
        }
        String s = q.trim();
        // Strip leading pipes, commas and whitespace
        s = s.replaceAll("^[\\|,\\s]+", "");
        // Strip trailing standalone OR/and operators
        s = s.replaceAll("\\s+(OR|or)\\s*$", "");
        // Remove common company suffixes
        s = s.replaceAll("(주식회사|㈜|Inc\\.|Co\\.,?\\s*Ltd\\.|Technologies|테크놀로지스)", " ").trim();
        // Take the first token
        String[] parts = s.split("\\s+");
        String first = parts.length > 0 ? parts[0] : s;
        return "\"" + first + "\"";
    }

    /**
     * Produce a small set of query variants from the cleaned form.  If the
     * cleaned string contains the specific Korean company name
     * "사이테크놀로지스" then a transliterated English version is
     * generated.  Otherwise only the cleaned query itself is returned.
     *
     * @param cleaned the cleaned query (must not be null)
     * @return a list of query variants
     */
    public List<String> expandVariants(String cleaned) {
        List<String> out = new ArrayList<>();
        out.add(cleaned);
        if (cleaned != null && cleaned.contains("사이테크놀로지스")) {
            out.add(cleaned.replace("사이테크놀로지스", "SAI Technologies"));
        }
        return out;
    }

    /**
     * Detect whether a query appears to reference a corporation by
     * inspecting for known suffixes such as "주식" or "Inc".  This
     * heuristic is not exhaustive but suffices for basic filtering.
     *
     * @param q the raw query
     * @return true when the query likely references a corporation
     */
    public static boolean looksLikeCorp(String q) {
        if (q == null) {
            return false;
        }
        return q.contains("주식") || q.contains("㈜") || q.toLowerCase().contains("inc") || q.contains("테크놀로지스");
    }
}
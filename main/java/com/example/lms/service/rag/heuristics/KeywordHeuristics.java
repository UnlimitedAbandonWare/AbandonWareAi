package com.example.lms.service.rag.heuristics;

import java.util.ArrayList;
import java.util.List;



/**
 * A simple heuristic extractor used when the self-ask LLM fails or times
 * out.  It strips out common corporate suffixes and punctuation and
 * returns the first token as a conservative estimate of the primary
 * entity.  This implementation is intentionally naïve; callers should
 * treat the output as a hint rather than a definitive keyword list.
 */
public class KeywordHeuristics {

    /**
     * Extract the most salient keyword from the provided text.  The
     * implementation removes non-alphanumeric characters, trims common
     * corporate suffixes and splits on whitespace.  If no tokens remain an
     * empty list is returned.
     *
     * @param text the input query (may be null)
     * @return a list containing zero or one keyword
     */
    public List<String> extractCoreKeywords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }
        // Replace punctuation with spaces to avoid concatenating words
        String cleaned = text.replaceAll("[^\\p{L}\\p{N} ]", " ");
        // Strip common company suffixes (Korean and English)
        cleaned = cleaned.replaceAll("(주식회사|㈜|Inc\\.|Co\\.|Ltd\\.|Technologies|테크놀로지스)", " ").trim();
        String[] tokens = cleaned.split("\\s+");
        if (tokens.length == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(tokens[0]);
        return out;
    }
}
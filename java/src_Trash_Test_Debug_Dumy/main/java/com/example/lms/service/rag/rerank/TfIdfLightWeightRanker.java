package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * A lightweight first‑pass ranker based on simple TF‑IDF scoring.  It operates
 * solely on the provided list of candidates and does not rely on any
 * external libraries or global corpora.  Tokens are extracted using a
 * locale‑aware regex that preserves letters (including Hangul) and digits
 * while discarding punctuation.  Document frequency is computed across
 * the candidates for each query token, and the resulting TF‑IDF score for
 * a document is the sum of tf(t) * idf(t) for all query tokens t.  When
 * the query contains no valid tokens the ranker falls back to the
 * original ordering.  A default limit of 12 is enforced when the
 * provided limit is non‑positive or exceeds the number of candidates.
 */

@Component
@Primary
public class TfIdfLightWeightRanker implements LightWeightRanker {

    private static final int DEFAULT_LIMIT = 12;

    @Override
    public List<Content> rank(List<Content> candidates, String query, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        final int n = candidates.size();
        // Determine the number of results to return.  When limit is
        // unspecified (<=0) or larger than the candidate set fall back to
        // DEFAULT_LIMIT and clamp to the total number of candidates.
        int k = (limit <= 0) ? DEFAULT_LIMIT : limit;
        if (k > n) {
            k = n;
        }
        // Tokenise the query.  If no tokens remain, simply return the
        // first k documents in their original order as a safe fallback.
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return new ArrayList<>(candidates.subList(0, k));
        }
        // Prepare term frequency maps for each document and compute
        // document frequencies for each query token.  Document
        // frequencies are only tracked for tokens that appear in the
        // query to reduce overhead.
        List<Map<String, Integer>> docTfList = new ArrayList<>(n);
        Map<String, Integer> dfMap = new HashMap<>();
        for (Content c : candidates) {
            // Extract the text of the candidate.  Use the textSegment if
            // present; otherwise fall back to toString().
            String text = (c.textSegment() != null) ? c.textSegment().text() : String.valueOf(c);
            Set<String> tokens = tokenize(text);
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            // Count document frequency for query tokens
            for (String qt : queryTokens) {
                if (tokens.contains(qt)) {
                    dfMap.merge(qt, 1, Integer::sum);
                }
            }
            docTfList.add(tf);
        }
        // Compute IDF values for each query token.  We add 1 to both
        // numerator and denominator to avoid division by zero and take
        // logarithm to dampen the effect of very common tokens.  An
        // additional +1 ensures idf is always positive.
        final int docCount = n;
        Map<String, Double> idfMap = new HashMap<>();
        for (String t : queryTokens) {
            int df = dfMap.getOrDefault(t, 0);
            double idf = Math.log((docCount + 1.0) / (df + 1.0)) + 1.0;
            idfMap.put(t, idf);
        }
        // Score each document as the sum of tf(t) * idf(t) over all
        // query tokens.  Documents with no overlap will receive a zero
        // score but are still retained in the list.
        record Scored(int idx, double score) {}
        List<Scored> scored = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = docTfList.get(i);
            double score = 0.0;
            for (String qt : queryTokens) {
                int freq = tf.getOrDefault(qt, 0);
                if (freq > 0) {
                    score += freq * idfMap.get(qt);
                }
            }
            scored.add(new Scored(i, score));
        }
        // Sort documents by descending score.  Ties are left in the order
        // they appear in the candidate list because Java's stable sort
        // preserves insertion order for equal keys.
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Content> result = new ArrayList<>(k);
        for (int i = 0; i < k && i < scored.size(); i++) {
            result.add(candidates.get(scored.get(i).idx()));
        }
        return result;
    }

    /**
     * Tokenise a string into a set of lowercase terms, retaining
     * alphabetic characters (including Hangul), digits and spaces.  Tokens
     * of length one are discarded to reduce noise.  An empty or null
     * input yields an empty set.
     *
     * @param s the input string
     * @return a set of tokens extracted from the input
     */
    private Set<String> tokenize(String s) {
        if (s == null || s.isBlank()) {
            return java.util.Collections.emptySet();
        }
        // Normalise to lowercase and replace any non‑letter/digit character
        // sequences with a single space.  \p{L} matches any kind of
        // letter from any language, \p{Nd} matches any decimal digit.
        String normalised = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ");
        String[] parts = normalised.trim().split("\\s+");
        Set<String> out = new LinkedHashSet<>();
        for (String part : parts) {
            if (part.length() > 1) {
                out.add(part);
            }
        }
        return out;
    }
}
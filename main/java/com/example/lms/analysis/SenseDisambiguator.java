package com.example.lms.analysis;

import java.util.List;



/**
 * A simple interface for determining the most likely senses of an ambiguous
 * query.  When a user enters a term that may refer to multiple entities
 * (e.g. “DW 아카데미”), the retrieval layer can consult a
 * {@code SenseDisambiguator} to rank possible interpretations based on
 * lightweight heuristics.  The resulting list of senses is ordered from
 * highest to lowest score along with the absolute difference between the
 * top two scores.  Callers can invoke {@link SenseResult#isAmbiguous(double)}
 * with a threshold to decide whether clarification is required.
 */
public interface SenseDisambiguator {

    /**
     * Compute candidate senses for the supplied query and a peek at the
     * highest ranked web snippets.  The implementation may inspect the
     * snippets for language, domain and geographic signals to bias the
     * ranking.  When the list of snippets is unavailable the disambiguator
     * should still return a default ordering of senses.
     *
     * @param query the raw user query
     * @param peekTopK a list of the top web results, in descending order
     * @return a {@code SenseResult} containing up to two candidate senses
     */
    SenseResult candidates(String query, List<?> peekTopK);

    /**
     * Represents a single candidate sense along with its computed score and
     * human-readable label.  Higher scores denote a greater likelihood that
     * the sense is relevant for the current user query and context.
     *
     * @param id    a machine readable identifier for the sense
     * @param score the numeric score assigned to this sense
     * @param label a human readable label for this sense
     */
    record Sense(String id, double score, String label) {}

    /**
     * Encapsulates the list of candidate senses and the absolute difference
     * between the top two scores.  When multiple senses are present and the
     * difference between the top two is below a caller supplied threshold
     * {@code tau}, the query should be considered ambiguous and a
     * clarification question should be posed to the user.
     *
     * @param senses the ordered list of candidate senses
     * @param delta  the absolute difference between the top two scores
     */
    record SenseResult(List<Sense> senses, double delta) {
        /**
         * Returns {@code true} when at least two senses are present and the
         * absolute score difference between the top two senses is below the
         * supplied threshold.  Use this to decide whether to ask the user
         * for clarification.
         *
         * @param tau the ambiguity threshold
         * @return whether the query is ambiguous
         */
        public boolean isAmbiguous(double tau) {
            return senses != null
                    && senses.size() > 1
                    && Math.abs(senses.get(0).score() - senses.get(1).score()) < tau;
        }
    }
}
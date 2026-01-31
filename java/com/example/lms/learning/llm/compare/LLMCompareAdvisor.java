package com.example.lms.learning.llm.compare;

import com.example.lms.compare.common.CompareResult;
import com.example.lms.compare.state.CompareState;
import java.util.ArrayList;
import java.util.List;




/**
 * Simple advisor that can convert a free-form natural language utterance
 * into a {@link CompareState} and provide explanations or coaching about
 * comparison results. This class is deliberately minimal; real
 * implementations would use NLP techniques to extract entities and criteria
 * from the utterance and generate nuanced explanations citing evidence.
 */
public class LLMCompareAdvisor {

    /**
     * Parse a user utterance into a {@link CompareState}. This naive
     * implementation splits on common comparison keywords and guesses
     * entities. It does not attempt to infer criteria or weights.
     *
     * @param text user utterance
     * @return a CompareState with inferred entities and default values
     */
    public CompareState parseFromUtterance(String text) {
        if (text == null || text.isBlank()) {
            return new CompareState(List.of(), List.of(), null, List.of(), null, false);
        }
        // very simple extraction: split on "vs" or commas to get entities
        String lower = text.toLowerCase();
        String[] parts;
        if (lower.contains(" vs ")) {
            parts = text.split("(?i)\\s*vs\\s*");
        } else if (text.contains(",")) {
            parts = text.split(",");
        } else {
            parts = new String[]{text};
        }
        List<String> entities = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.strip();
            if (!trimmed.isEmpty()) {
                entities.add(trimmed);
            }
        }
        return new CompareState(entities, List.of(), null, List.of(), null, false);
    }

    /**
     * Generate a natural language explanation for a comparison result. This
     * shim implementation simply summarises the ranking order.
     *
     * @param in the input used for comparison
     * @param out the result of the comparison
     * @return a human readable explanation
     */
    public String explain(CompareState in, CompareResult out) {
        if (out == null || out.ranking().isEmpty()) {
            return "No comparison could be made due to missing information.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Comparison result: \n");
        int rank = 1;
        for (CompareResult.ScoredEntity se : out.ranking()) {
            sb.append(rank++).append(". ").append(se.name()).append(" (score=")
                    .append(String.format("%.2f", se.score())).append(")\n");
        }
        return sb.toString();
    }

    /**
     * Provide coaching tips for making a selection. This naive implementation
     * encourages the user to consider the listed criteria and think about
     * personal priorities.
     *
     * @param state comparison definition
     * @return a coaching tip
     */
    public String coach(CompareState state) {
        List<String> criteria = state.criteria();
        if (criteria == null || criteria.isEmpty()) {
            return "Consider what matters most to you such as price, performance or reliability when choosing.";
        }
        return "Weigh your options based on criteria like " + String.join(", ", criteria) + 
                ". Think about which aspects align with your personal priorities.";
    }
}
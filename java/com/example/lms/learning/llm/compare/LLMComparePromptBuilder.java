package com.example.lms.learning.llm.compare;

import com.example.lms.compare.common.CompareResult;
import com.example.lms.compare.state.CompareState;



/**
 * Utility for constructing prompts for the language model when performing
 * comparative reasoning. This builder produces a simple template that
 * includes the comparison input, optional scoring breakdown and evidence
 * summaries. Real implementations would integrate external evidence and
 * enforce citation formatting.
 */
public class LLMComparePromptBuilder {

    /**
     * Build a prompt string from the supplied state and result. This template
     * includes sections for the input definition, the score breakdown and
     * evidence. In this default implementation the evidence section is left
     * blank as no external sources are consulted.
     *
     * @param state comparison definition
     * @param result computed result
     * @return a complete prompt ready for LLM consumption
     */
    public String buildPrompt(CompareState state, CompareResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("### COMPARE INPUT\n");
        sb.append(state).append("\n\n");
        sb.append("### SCORE BREAKDOWN\n");
        sb.append(result != null ? result.breakdown() : "{}").append("\n\n");
        sb.append("### EVIDENCE\n");
        sb.append("(No external evidence available)\n");
        return sb.toString();
    }
}
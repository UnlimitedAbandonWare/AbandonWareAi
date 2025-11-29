package com.example.lms.prompt;

import org.springframework.stereotype.Component;
import java.util.List;



/**
 * Constructs prompts for the query disambiguation workflow.  This builder
 * encapsulates the multi-line template previously inlined in
 * {@code QueryDisambiguationService}.  All callers should obtain an
 * instance of this builder via Spring injection and use {@link #build}
 * to assemble the prompt rather than constructing ad-hoc strings.  This
 * centralises prompt maintenance and enforces consistency across the
 * application.
 */
@Component
public class DisambiguationPromptBuilder {

    /**
     * Build a disambiguation prompt.  The returned text instructs the
     * language model to clarify ambiguous terms and return a JSON object
     * containing the resolved intent and rewritten query.  When the query
     * history is empty or null the history section will be omitted.
     *
     * @param query   the current user query (may be null)
     * @param history optional list of prior messages, oldest to latest
     * @return a complete prompt ready for LLM consumption
     */
    public String build(String query, List<String> history) {
        String hist;
        if (history == null || history.isEmpty()) {
            hist = "";
        } else {
            hist = String.join("\n", history);
        }
        // Protect against null query to avoid a NullPointerException in
        // String.format.  A null query is treated as an empty string.
        String q = query == null ? "" : query;
        return String.format("""
                You are an intent disambiguator. Return ONLY a JSON object with fields:
                ambiguousTerm, resolvedIntent, rewrittenQuery, confidence, score.
                If not ambiguous, set rewrittenQuery to the original query and confidence="low".

                RULES:
                - Do NOT invent characters/items/places that do not exist in the referenced domain (e.g., Genshin Impact).
                - If the user query includes a proper noun that the system already recognizes (in-domain dictionary),
                  DO NOT rewrite or append any notes. Keep the original query as rewrittenQuery and set confidence="high".
                - Do not append speculative notes such as "(존재하지 않는 요소 가능성)". Such notes are prohibited.

                [Conversation history, oldest→latest]
                %s

                [Current query]
                %s
                """, hist, q);
    }
}
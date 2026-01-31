package com.example.lms.dto.learning;

import java.util.List;



/**
 * Represents the structured knowledge output from Gemini. It includes lists of
 * triples, induced rules, aliases, memory snippets and protected terms.
 * All lists default to empty lists to avoid null handling downstream.
 */
public record KnowledgeDelta(
        List<Triple> triples,
        List<Rule> rules,
        List<Alias> aliases,
        List<MemorySnippet> memories,
        List<Term> protectedTerms
) {
    public KnowledgeDelta {
        triples        = triples        == null ? List.of() : List.copyOf(triples);
        rules          = rules          == null ? List.of() : List.copyOf(rules);
        aliases        = aliases        == null ? List.of() : List.copyOf(aliases);
        memories       = memories       == null ? List.of() : List.copyOf(memories);
        protectedTerms = protectedTerms == null ? List.of() : List.copyOf(protectedTerms);
    }
}
package com.example.lms.dto.answer;

import java.util.List;
import java.util.Objects;



/**
 * Represents a structured summary of the assistant's final answer.  This record
 * captures the high-level gist (TL;DR), key bullet points, actionable next
 * steps, decisions, risks, follow-ups and optional glossary, entity and
 * citation data.  A confidence score between 0.0 and 1.0 reflects the model's
 * self-reported certainty about the summary.  Fields may be null or empty
 * depending on the input and summarization strategy.
 */
public record AnswerUnderstanding(
        String tldr,
        List<String> keyPoints,
        List<String> actionItems,
        List<String> decisions,
        List<String> risks,
        List<String> followUps,
        List<AnswerUnderstanding.GlossaryEntry> glossary,
        List<AnswerUnderstanding.Entity> entities,
        List<AnswerUnderstanding.Citation> citations,
        double confidence
) {
    /**
     * Nested record representing a glossary entry with a term and its definition.
     */
    public static record GlossaryEntry(String term, String definition) {
        public GlossaryEntry {
            Objects.requireNonNull(term, "term must not be null");
            Objects.requireNonNull(definition, "definition must not be null");
        }
    }

    /**
     * Nested record representing a named entity and its type.
     */
    public static record Entity(String name, String type) {
        public Entity {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    /**
     * Nested record representing a citation with a URL and title.  When the
     * assistant references external sources these citations can be used to
     * hyperlink back to the original material.
     */
    public static record Citation(String url, String title) {
        public Citation {
            Objects.requireNonNull(url, "url must not be null");
            Objects.requireNonNull(title, "title must not be null");
        }
    }
}
package com.acme.aicore.domain.model;


/**
 * Represents an incremental piece of a model’s generated output.  Used when
 * streaming responses via SSE.  The {@link AnswerChunk} record is similar
 * but distinguished to illustrate the difference between token-level and
 * higher-level streaming.
 */
public record TokenChunk(String text) {
    public static TokenChunk of(String text) { return new TokenChunk(text); }
}
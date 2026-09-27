package com.acme.aicore.domain.model;


/**
 * A piece of the model’s answer.  Streaming models emit responses in chunks
 * rather than a single concatenated string.  A chunk contains text and
 * optionally other metadata such as citations or finish reason.  For
 * simplicity this record wraps only the text.
 */
public record AnswerChunk(String text) {
    public static AnswerChunk from(String text) {
        return new AnswerChunk(text);
    }

    public static AnswerChunk of(String text) {
        return new AnswerChunk(text);
    }
}
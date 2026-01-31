package com.acme.aicore.domain.model;


/**
 * Parameters controlling model generation.
 * Extend with temperature, topP, stop sequences, etc. as needed.
 */
public class GenerationParams {

    private final boolean streaming;

    private GenerationParams(boolean streaming) {
        this.streaming = streaming;
    }

    /** Accessor uses bean-style to avoid name clash with static factory. */
    public boolean isStreaming() {
        return streaming;
    }

    /** Factory: streaming mode (server-sent events, etc.). */
    public static GenerationParams streaming() {
        return new GenerationParams(true);
    }

    /** Factory: single-shot completion mode. */
    public static GenerationParams complete() {
        return new GenerationParams(false);
    }
}
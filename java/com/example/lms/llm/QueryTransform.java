package com.example.lms.llm;

/**
 * Lightweight transform interface for vector pre-processing (compile-only shim).
 */
public interface QueryTransform {
    /**
     * Preprocesses the given embedding vector. Implementations may apply whitening or other transforms.
     *
     * @param vec input embedding vector, may be null
     * @return processed vector; never null
     */
    float[] apply(float[] vec);
}
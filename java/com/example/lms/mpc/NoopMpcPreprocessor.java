package com.example.lms.mpc;

/**
 * NoopMpcPreprocessor
 * - Default safe implementation that passes data through unchanged.
 */
public class NoopMpcPreprocessor implements MpcPreprocessor {

    @Override
    public Object normalizeVoxel(Object payload) {
        return payload; // no-op
    }
}
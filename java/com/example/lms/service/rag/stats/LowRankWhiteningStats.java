package com.example.lms.service.rag.stats;

import org.springframework.stereotype.Component;



/**
 * Compile-only no-op whitening stats provider.
 * Until actual whitening statistics are available, this implementation simply returns the input vector.
 */
@Component
public class LowRankWhiteningStats {

    /**
     * Safely transforms the vector. If the input is null this returns an empty array.
     *
     * @param v input vector, may be null
     * @return the same vector or a new empty vector if null
     */
    public float[] transform(float[] v) {
        return v != null ? v : new float[0];
    }
}
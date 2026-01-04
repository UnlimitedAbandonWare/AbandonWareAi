package com.example.lms.mpc;

/**
 * MpcPreprocessor
 * - Extension point for 3D/Voxel preprocessing (mirror-transform perfect cube).
 * - This interface intentionally avoids framework annotations to compile anywhere.
 */
public interface MpcPreprocessor {
    /**
     * Normalize incoming volume-like payload to a canonical perfect cube.
     * Return input unchanged if not applicable.
     */
    Object normalizeVoxel(Object payload);
}
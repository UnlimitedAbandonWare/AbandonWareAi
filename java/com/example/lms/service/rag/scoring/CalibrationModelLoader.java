package com.example.lms.service.rag.scoring;

import java.io.File;

/**
 * Loads an {@link IsotonicCalibrator} when scoring.calibration.enabled=true.
 */
public class CalibrationModelLoader {

    private final boolean enabled;
    private final String modelPath;

    public CalibrationModelLoader() {
        this.enabled = Boolean.parseBoolean(System.getProperty("scoring.calibration.enabled", "false"));
        this.modelPath = System.getProperty("scoring.calibration.model-path", "");
    }

    public boolean isEnabled() { return enabled; }

    public IsotonicCalibrator get() {
        if (!enabled) return null;
        File f = (modelPath == null || modelPath.isEmpty()) ? null : new File(modelPath);
        return IsotonicCalibrator.load(f);
    }
}
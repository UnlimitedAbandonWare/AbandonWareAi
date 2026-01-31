package com.example.lms.artplate;


/**
 * Light-weight context passed into plate gate.
 * Values can be derived from current request, session telemetry, and quick probes.
 */
public record PlateContext(
    boolean useWeb, boolean useRag,
    int sessionRecur, int evidenceCount,
    double authority, boolean noisy,
    double webGate, double vectorGate, double memoryGate,
    double recallNeed
) { }
package com.abandonwareai.fusion;

import org.springframework.stereotype.Component;

/**
 * Simple Bode-like sensitivity clamp:
 * Interpret input as a linear gain g>=0; convert to decibels: G[dB]=20*log10(g+eps),
 * clamp |G| <= dbLimit, then map back to linear. If g<=0 → 0.
 */
@Component
public class BodeClamp {
    public double clamp(double g, double dbLimit) {
        if (!Double.isFinite(g) || g <= 0) return Math.max(0.0, g);
        double eps = 1e-12;
        double Gdb = 20.0 * Math.log10(g + eps);
        double limit = Math.abs(dbLimit);
        double GdbClamped = Math.max(-limit, Math.min(limit, Gdb));
        if (GdbClamped <= -300.0) return 0.0; // avoid underflow
        return Math.pow(10.0, GdbClamped / 20.0);
    }
}
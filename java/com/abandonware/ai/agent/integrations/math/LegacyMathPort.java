package com.abandonware.ai.agent.integrations.math;


/**
 * LegacyMathPort
 * Source: lms-src-legacy_20250905T0100.zip (exact formulas to be ported 1:1)
 * NOTE: This placeholder keeps API stable; replace methods with exact legacy implementations.
 */
public class LegacyMathPort {
    public static double clamp(double x, double lo, double hi){ return Math.max(lo, Math.min(hi, x)); }
    public static double sigmoid(double x){ return 1.0 / (1.0 + Math.exp(-x)); }
    public static double safePow(double x, double p){ try { return Math.pow(x, p); } catch(Exception e){ return 0.0; } }
}
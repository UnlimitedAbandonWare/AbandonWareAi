package com.example.lms.service.rag.fusion.impl;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.fusion.impl.ChainedFusionCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.example.lms.service.rag.fusion.impl.ChainedFusionCalibrator
role: config
*/
public class ChainedFusionCalibrator implements com.example.lms.service.rag.fusion.FusionCalibrator {

    public static final class PlattParams {
        public final double A, B;
        public PlattParams(double A, double B) { this.A=A; this.B=B; }
        public double apply(double z) { return 1.0/(1.0 + Math.exp(A*z + B)); }
    }

    private final Map<String, PlattParams> platt = new ConcurrentHashMap<>();
    private final Map<String, NavigableMap<Double, Double>> iso = new ConcurrentHashMap<>();
    private final Map<String, Double> temperature = new ConcurrentHashMap<>();

    @Override
    public double calibrate(String source, double rawScore) {
        double s = rawScore;
        PlattParams pp = platt.get(source);
        if (pp != null) {
            s = pp.apply(s);
        }
        NavigableMap<Double,Double> pav = iso.get(source);
        if (pav != null && !pav.isEmpty()) {
            Map.Entry<Double,Double> lo = pav.floorEntry(s);
            Map.Entry<Double,Double> hi = pav.ceilingEntry(s);
            if (lo==null && hi!=null) s = hi.getValue();
            else if (hi==null && lo!=null) s = lo.getValue();
            else if (lo!=null && hi!=null) {
                // linear interpolation in isotonic segments
                if (Math.abs(hi.getKey()-lo.getKey())<1e-12) s = 0.5*(lo.getValue()+hi.getValue());
                else {
                    double t = (s-lo.getKey())/(hi.getKey()-lo.getKey());
                    s = lo.getValue()*(1.0-t) + hi.getValue()*t;
                }
            }
        }
        Double T = temperature.get(source);
        if (T != null && T>1e-9) {
            // logistic temperature scaling for scalar scores
            s = 1.0/(1.0 + Math.exp(-s / T));
        }
        // Clamp to [0,1]
        if (s<0) s=0; if (s>1) s=1;
        return s;
    }

    // Simple registration API (no persistence here; wire from config/service)
    public void registerPlatt(String source, double A, double B) { platt.put(source, new PlattParams(A,B)); }
    public void registerIsotonic(String source, NavigableMap<Double,Double> pav) { iso.put(source, pav); }
    public void registerTemperature(String source, double T) { temperature.put(source, T); }
}
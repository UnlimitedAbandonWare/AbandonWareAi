package com.example.lms.service.rag.fusion;

import java.util.*;

/**
 * Weighted Reciprocal Rank Fusion.
 * Self-contained, no external calibrator deps to keep the app module buildable.
 */
\1

private java.util.List<Double> weights = java.util.Arrays.asList(1.0,1.0,1.0);
public void setWeights(java.util.List<Double> w){ if(w!=null && !w.isEmpty()) this.weights = w; }
private double weightFor(String source){
    if(source==null) return 1.0;
    String s = source.toLowerCase();
    if(s.contains("kg")) return 1.2;
    if(s.contains("vector")) return 0.8;
    return 1.0;
}

    private Object externalCanonicalizer; // com.example.lms.service.rag.canon.RerankCanonicalizer
    public void setExternalCanonicalizer(Object can){ this.externalCanonicalizer = can; }

    private Object externalCalibrator; // com.example.lms.service.rag.scoring.ScoreCalibrator
    public void setExternalCalibrator(Object calibrator){ this.externalCalibrator = calibrator; }


    public enum Mode { RRF, WPM }
    private Mode mode = Mode.RRF;
    private double p = 1.0; // WPM exponent: p=1 arithmetic, p->inf max, p->0 geometric

    public void setMode(String m){
        if (m==null) return;
        try { this.mode = Mode.valueOf(m.trim().toUpperCase()); } catch (Exception ignore) {}
    }
    public void setP(double p){ if (p>0) this.p = p; }


    public static final class Candidate {
        public final String id;
        public final String source;
        public final double baseScore;
        public int rank;
        public double fused;

        public Candidate(String id, String source, double baseScore, int rank) {
            this.id = id;
            this.source = source;
            this.baseScore = baseScore;
            this.rank = rank;
        
    // --- P0-3: URL canonicalization & simple per-source score calibration ---
    private String canonical(String idOrUrl){
        try{
            if (externalCanonicalizer != null){
                java.lang.reflect.Method m = externalCanonicalizer.getClass().getMethod("canonicalUrl", String.class);
                Object out = m.invoke(externalCanonicalizer, idOrUrl);
                if (out instanceof String) return (String) out;
            }
        }catch(Exception ignore){}
        try {
            if (idOrUrl == null) return "";
            if (idOrUrl.startsWith("http://") || idOrUrl.startsWith("https://")) {
                java.net.URI u = java.net.URI.create(idOrUrl);
                String path = (u.getPath()==null?"":u.getPath()).replaceAll("/+$","");
                return u.getScheme()+"://"+u.getHost()+path;
            }
            return idOrUrl.trim().toLowerCase();
        } catch (Exception e){
            return idOrUrl==null? "" : idOrUrl;
        }
    }

    private double powerMean(Collection<Double> scores, double p){
        if (scores==null || scores.isEmpty()) return 0.0;
        if (Double.isInfinite(p)) { // max
            double mx = -Double.MAX_VALUE;
            for (double v: scores) if (v>mx) mx=v;
            return mx;
        }
        double acc = 0.0;
        for (double v: scores) acc += Math.pow(Math.max(v,1e-12), p);
        return Math.pow(acc / scores.size(), 1.0/p);
    }

    /** New overload: return fused score per id (for downstream RRF/WPM). */
    public Map<String,Double> fuse(Map<String, List<Candidate>> channels, double k, Map<String,Double> weights){
        this.k = (int) k;
        Map<String, Double> sum = new HashMap<>();
        Map<String, Candidate> any = new HashMap<>();
        for (Map.Entry<String,List<Candidate>> e : channels.entrySet()) {
            double w = (weights==null?1.0:weights.getOrDefault(e.getKey(),1.0));
            fuseInto(sum, any, e.getValue(), w);
        }
        if (mode==Mode.RRF) return sum;
        // convert to per-id collection of calibrated base scores for WPM
        Map<String, List<Double>> buckets = new HashMap<>();
        for (Map.Entry<String, Candidate> e : any.entrySet()){
            buckets.computeIfAbsent(e.getKey(), kk->new ArrayList<>()).add(e.getValue().baseScore);
        }
        Map<String,Double> fused = new HashMap<>();
        for (Map.Entry<String,List<Double>> e : buckets.entrySet()){
            fused.put(e.getKey(), powerMean(e.getValue(), p));
        }
        return fused;
    }
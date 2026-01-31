package com.example.rag.qc;


public final class QueryComplexityGate {
    public static final class Features {
        public int qlen;
        public double oovRatio;
        public double disagreementKendallTau;
        public double margin;
        public boolean hasWhyHowMultiHop;
        public boolean expectExactness;
    }
    private static double z(double v, double lo, double hi){
        double t = (v - lo) / (hi - lo + 1e-9);
        if (t < 0) t = 0; if (t > 1) t = 1;
        return t;
    }
    public boolean shouldRerank(Features f){
        double s = 0.0;
        s += z(f.qlen, 10, 30) * 0.2;
        s += z(f.oovRatio, 0.05, 0.2) * 0.2;
        s += z(1.0 - f.disagreementKendallTau, 0.2, 0.6) * 0.3;
        s += z(1.0 - f.margin, 0.1, 0.5) * 0.2;
        s += (f.hasWhyHowMultiHop ? 0.2 : 0.0);
        return s >= 0.6;
    }
}

package com.abandonware.ai.agent.integrations;


public final class Distance {
    private Distance() {}
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 1.0; // distance
        double dot = 0, na=0, nb=0;
        for (int i=0;i<a.length;i++) {
            float av = a[i];
            float bv = b[i];
            if (!Float.isFinite(av) || !Float.isFinite(bv)) {
                continue;
            }
            dot+= av*bv; na+=av*av; nb+=bv*bv;
        }
        if (na==0 || nb==0) return 1.0;
        double cosine = dot / Math.sqrt(na*nb);
        if (!Double.isFinite(cosine)) return 1.0;
        cosine = Math.max(-1.0, Math.min(1.0, cosine));
        return 1.0 - cosine;
    }
}

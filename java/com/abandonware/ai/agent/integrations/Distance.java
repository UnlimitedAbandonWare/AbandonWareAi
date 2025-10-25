
package com.abandonware.ai.agent.integrations;


public final class Distance {
    private Distance() {}
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 1.0; // distance
        double dot = 0, na=0, nb=0;
        for (int i=0;i<a.length;i++) { dot+= a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
        if (na==0 || nb==0) return 1.0;
        return 1.0 - dot / Math.sqrt(na*nb);
    }
}
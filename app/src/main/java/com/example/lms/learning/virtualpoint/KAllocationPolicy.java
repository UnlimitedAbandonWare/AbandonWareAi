package com.example.lms.learning.virtualpoint;

import java.util.*;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.learning.virtualpoint.KAllocationPolicy
 * Role: config
 * Feature Flags: kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.learning.virtualpoint.KAllocationPolicy
role: config
flags: [kg]
*/
public class KAllocationPolicy {

    public static final class Request {
        public final String query;
        public final int totalK;
        public Request(String query, int totalK){ this.query=query; this.totalK=totalK; }
    }

    public static final class Allocation {
        public final int webK, vectorK, kgK;
        public Allocation(int webK, int vectorK, int kgK){ this.webK=webK; this.vectorK=vectorK; this.kgK=kgK; }
        @Override public String toString(){
            return "Allocation(web="+webK+", vector="+vectorK+", kg="+kgK+")";
        }
    }

    public Allocation allocate(Request req){
        double[] phi = features(req.query);
        double beta = 2.0; // sharper gate
        double[] g = softmax(scale(phi, beta));
        int K = Math.max(1, req.totalK);
        int web = Math.max(1, (int)Math.round(K * g[0]));
        int vec = Math.max(1, (int)Math.round(K * g[1]));
        int kg  = Math.max(1, K - web - vec);
        return new Allocation(web, vec, kg);
    }

    private static double[] features(String q){
        String s = (q==null?"":q).toLowerCase(Locale.ROOT);
        boolean latest = s.contains("latest") || s.contains("최근") || s.contains("today");
        int len = s.length();
        // heuristic: newer intent -> favor web; long/technical -> favor vector; explicit entity -> favor kg
        double fWeb = latest ? 1.0 : 0.3;
        double fVec = Math.min(1.0, len/120.0);
        double fKg  = s.matches(".*\\b([A-Z][a-z]+\\s+[A-Z][a-z]+)\\b.*") ? 0.8 : 0.3;
        return new double[]{fWeb, fVec, fKg};
    }

    private static double[] softmax(double[] z){
        double max = Double.NEGATIVE_INFINITY;
        for(double v:z) if (v>max) max=v;
        double sum=0; double[] e = new double[z.length];
        for(int i=0;i<z.length;i++){ e[i]=Math.exp(z[i]-max); sum+=e[i]; }
        for(int i=0;i<z.length;i++) e[i]/=sum;
        return e;
    }
    private static double[] scale(double[] x, double b){ double[] y=new double[x.length]; for(int i=0;i<x.length;i++) y[i]=b*x[i]; return y; }
}
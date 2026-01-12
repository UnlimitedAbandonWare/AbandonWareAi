package com.abandonwareai.fusion;

import java.util.*;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.fusion.MpLawNormalizer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.fusion.MpLawNormalizer
role: config
*/
public class MpLawNormalizer {

    public static class Stats {
        public final double mu, sigma;
        public final double lambdaMinus, lambdaPlus;
        public Stats(double mu,double sigma,double lambdaMinus,double lambdaPlus){
            this.mu=mu; this.sigma=sigma; this.lambdaMinus=lambdaMinus; this.lambdaPlus=lambdaPlus;
        }
    }

    public double[] normalize(double[] scores){
        if (scores==null || scores.length==0) return scores;
        double[] x = scores.clone();
        Stats s = estimate(x);
        // Clamp into [lambdaMinus, lambdaPlus] around mu
        double[] y = new double[x.length];
        for (int i=0;i<x.length;i++){
            double z = x[i];
            double lo = s.mu + s.lambdaMinus;
            double hi = s.mu + s.lambdaPlus;
            if (z<lo) z=lo; if (z>hi) z=hi;
            // map to 0..1 with mu at ~0.5
            y[i] = (z - lo) / Math.max(1e-9, (hi - lo));
        }
        return y;
    }

    private Stats estimate(double[] x){
        int n=x.length;
        double mu=0; for(double v:x) mu+=v; mu/=n;
        double var=0; for(double v:x) var+=(v-mu)*(v-mu); var/=Math.max(1,n-1);
        double sigma = Math.sqrt(Math.max(var, 1e-12));
        // Use aspect ratio q approx from small sample; fallback q=0.25
        double q = 0.25;
        double lambdaMinus = -sigma*Math.pow(1.0-Math.sqrt(q),2);
        double lambdaPlus  =  sigma*Math.pow(1.0+Math.sqrt(q),2);
        return new Stats(mu,sigma,lambdaMinus,lambdaPlus);
    }
}
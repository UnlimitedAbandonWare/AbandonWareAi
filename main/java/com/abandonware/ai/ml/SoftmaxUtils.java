package com.abandonware.ai.ml;

public final class SoftmaxUtils {
    private SoftmaxUtils(){}

    public static double[] stableSoftmax(double[] logits, double temperature){
        double t = temperature <= 0 ? 1.0 : temperature;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : logits) if (v > max) max = v;
        double[] exp = new double[logits.length];
        double sum = 0.0;
        for (int i=0;i<logits.length;i++){
            exp[i] = Math.exp((logits[i] - max)/t);
            sum += exp[i];
        }
        if (sum <= 0) {
            double[] uniform = new double[logits.length];
            double u = logits.length==0?0:1.0/logits.length;
            for (int i=0;i<logits.length;i++) uniform[i] = u;
            return uniform;
        }
        for (int i=0;i<exp.length;i++) exp[i] /= sum;
        return exp;
    }
}
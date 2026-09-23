package service.rag.fusion;

public final class PowerMeanCombiner {
    private PowerMeanCombiner(){}
    public static double combine(double[] scores, double[] weights, double power){
        if (scores == null || scores.length == 0) return 0.0;
        int n = scores.length;
        double num = 0.0, den = 0.0;
        for (int i=0;i<n;i++){
            double s = Math.max(0.0, scores[i]);
            double w = (weights!=null && i<weights.length) ? Math.max(0.0, weights[i]) : 1.0;
            num += w * Math.pow(s, power);
            den += w;
        }
        if (den <= 0) return 0.0;
        return Math.pow(num/den, 1.0/power);
    }
}
package strategy;

public final class SoftmaxUtil {
    private SoftmaxUtil(){}

    /**
     * Numerically stable softmax with temperature.
     * @param z logits
     * @param T temperature (>0). Lower T => more peaky.
     * @return probabilities summing to 1.0
     */
    public static double[] softmax(double[] z, double T) {
        if (z == null || z.length == 0) return new double[0];
        if (T <= 0) T = 1.0;
        int n = z.length;
        double[] out = new double[n];
        double max = z[0];
        for (int i=1;i<n;i++) if (z[i] > max) max = z[i];
        double sum = 0.0;
        for (int i=0;i<n;i++) {
            out[i] = Math.exp((z[i] - max) / T);
            sum += out[i];
        }
        if (sum <= 0) {
            double p = 1.0 / n;
            for (int i=0;i<n;i++) out[i] = p;
            return out;
        }
        for (int i=0;i<n;i++) out[i] /= sum;
        return out;
    }
}
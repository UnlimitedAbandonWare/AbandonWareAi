package router.joiner;

public class ActivationJoiner {
    // Compute final S from 5-element sigma values
    public static double join(double sigmaP, double sigmaR, double sigmaC, double sigmaY, double sigmaK){
        double[] w = new double[]{0.2,0.2,0.2,0.2,0.2};
        double sum = w[0]*sigmaP + w[1]*sigmaR + w[2]*sigmaC + w[3]*sigmaY + w[4]*sigmaK;
        double beta = 0.5;
        double z = 8.0*(sum - beta);
        return 1.0/(1.0 + Math.exp(-z));
    }
}
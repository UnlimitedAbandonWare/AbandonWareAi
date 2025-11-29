package app.gate;
public class FinalSigmoidGate {
    private final double k;
    private final double x0;
    public FinalSigmoidGate(double k, double x0) {
        this.k = k; this.x0 = x0;
    }
    public boolean pass(double score) {
        double s = 1.0 / (1.0 + Math.exp(-k * (score - x0)));
        return s >= 0.90; // pass9x threshold
    }
}

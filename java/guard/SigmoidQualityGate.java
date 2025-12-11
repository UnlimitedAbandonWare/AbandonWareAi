package guard;

/**
 * Sigmoid-based final quality gate. Non-invasive: can be used in parallel with citation gate.
 */
public class SigmoidQualityGate {
    private final double k;
    private final double x0;
    private final double pass;

    public SigmoidQualityGate(double k, double x0, double passThreshold) {
        this.k = k; this.x0 = x0; this.pass = passThreshold;
    }

    public boolean accept(double x) {
        double s = 1.0 / (1.0 + Math.exp(-k * (x - x0)));
        return s >= pass;
    }
}
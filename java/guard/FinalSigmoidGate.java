package guard;

/** Final gate to allow only high-confidence contexts to proceed. */
public class FinalSigmoidGate {
  public double s(double x, double k, double x0){ return 1.0/(1.0 + Math.exp(-k*(x - x0))); }
  public boolean pass(double x){ return s(x, 10.0, 0.90) >= 0.5; }
}
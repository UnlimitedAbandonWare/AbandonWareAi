package service.rag.score;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: service.rag.score.MinMaxScoreCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: service.rag.score.MinMaxScoreCalibrator
role: config
*/
public class MinMaxScoreCalibrator implements ScoreCalibrator {
  private final double min;
  private final double max;
  public MinMaxScoreCalibrator(double min, double max) { this.min = min; this.max = max; }
  @Override public double apply(double raw) { return ScoreCalibrator.safe(raw, min, max); }
}
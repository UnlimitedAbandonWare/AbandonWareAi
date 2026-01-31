package service.rag.score;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: service.rag.score.ScoreCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: service.rag.score.ScoreCalibrator
role: config
*/
public interface ScoreCalibrator {
  double apply(double raw);
  static double safe(double raw, double min, double max) {
    if (Double.isNaN(raw)) return 0.0;
    if (max <= min) return 0.0;
    return (raw - min) / (max - min);
  }
}
package strategy.score;

public interface ScoreCalibrator {
  double calibrate(double raw);
}
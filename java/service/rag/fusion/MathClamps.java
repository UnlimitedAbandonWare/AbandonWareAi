package service.rag.fusion;

/** Bode-like smooth clamp into (0,1). */
public class MathClamps {
  public static double bodeClamp(double x, double gain){
    return Math.tanh(gain * (x - 0.5)) * 0.5 + 0.5;
  }
}
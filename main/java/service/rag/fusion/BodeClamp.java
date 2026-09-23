package service.rag.fusion;
public class BodeClamp {
  public double clamp(double v, double gain){ return Math.tanh(gain * v); }
}
package strategy.core.budget;

public class TimeBudget {
  private final long start = System.currentTimeMillis();
  private final long totalMs;
  public TimeBudget(long totalMs) { this.totalMs = totalMs; }
  public long remaining() { long used = System.currentTimeMillis()-start; return Math.max(0, totalMs - used); }
  public TimeBudget child(double fraction) { return new TimeBudget((long)(totalMs * fraction)); }
  public CancellationToken cancellationToken() { return new CancellationToken(this); }
}
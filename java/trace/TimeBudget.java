package trace;

public class TimeBudget {
  private final long deadlineMs;
  public TimeBudget(long totalMs){ this.deadlineMs = System.currentTimeMillis()+totalMs; }
  public long remainingMs(){ return Math.max(0, deadlineMs - System.currentTimeMillis()); }
  public boolean exhausted(){ return remainingMs() <= 0; }
}
package service.guard;

public class TimeBudget {
  private final long deadlineMs;
  public TimeBudget(long ttlMs){ this.deadlineMs = System.currentTimeMillis()+ttlMs; }
  public boolean isExpired(){ return System.currentTimeMillis()>deadlineMs; }
  public long remainingMs(){ return Math.max(0, deadlineMs - System.currentTimeMillis()); }
}
package com.example.lms.service.runtime;
public final class TimeBudget {
  private final long deadlineMs;
  public TimeBudget(long deadlineMs){ this.deadlineMs=deadlineMs; }
  public long remainingMillis(){ return Math.max(0L, deadlineMs - System.currentTimeMillis()); }
  public boolean expired(){ return remainingMillis()<=0L; }
  public long getDeadlineMs(){ return deadlineMs; }
}

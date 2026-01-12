package com.example.lms.service.rag.zsystem;

public class TimeBudget {
    private final long deadlineMs;
    public TimeBudget(long deadlineMs) { this.deadlineMs = deadlineMs; }
    public boolean expired(){ return System.currentTimeMillis() > deadlineMs; }
    public long left(){ return Math.max(0, deadlineMs - System.currentTimeMillis()); }
    public static TimeBudget fromNow(long ms){ return new TimeBudget(System.currentTimeMillis()+ms); }
}
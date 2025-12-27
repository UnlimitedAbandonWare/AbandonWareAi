package com.example.lms.uaw.autolearn;

/**
 * Serializable state for budget management.
 */
public class AutoLearnRunState {
    /** yyyy-MM-dd (server local). */
    public String day;
    public int runsToday;
    public long lastStartEpochMs;
    public long lastFinishEpochMs;
    public int consecutiveFailures;
    public long backoffUntilEpochMs;

    public static AutoLearnRunState newDefault(String day) {
        AutoLearnRunState s = new AutoLearnRunState();
        s.day = day;
        s.runsToday = 0;
        s.lastStartEpochMs = 0L;
        s.lastFinishEpochMs = 0L;
        s.consecutiveFailures = 0;
        s.backoffUntilEpochMs = 0L;
        return s;
    }
}

package trace;

public class TimeBudget {
    private final long deadlineMs;

    public TimeBudget(long deadlineMs) {
        this.deadlineMs = deadlineMs;
    }

    public boolean expired() {
        return System.currentTimeMillis() > deadlineMs;
    }

    public long remain() {
        long r = deadlineMs - System.currentTimeMillis();
        return r > 0 ? r : 0L;
    }
}
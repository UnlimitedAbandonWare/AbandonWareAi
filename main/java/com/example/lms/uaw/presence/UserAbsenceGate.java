package com.example.lms.uaw.presence;

import org.springframework.stereotype.Component;

/**
 * Computes whether the user is absent right now.
 */
@Component
public class UserAbsenceGate {

    private final UserPresenceTracker tracker;
    private final UserPresenceProperties props;

    public UserAbsenceGate(UserPresenceTracker tracker, UserPresenceProperties props) {
        this.tracker = tracker;
        this.props = props;
    }

    public boolean isUserAbsentNow() {
        if (tracker.inflight() > 0) return false;
        long quietMs = Math.max(0, props.getQuietSeconds()) * 1000L;
        long since = System.currentTimeMillis() - tracker.lastUserRequestFinishedAt();
        return since >= quietMs;
    }
}

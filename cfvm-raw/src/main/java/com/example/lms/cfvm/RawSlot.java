package com.example.lms.cfvm;

import java.time.Instant;
import java.util.Objects;

/**
 * Minimal immutable slot describing one raw build log observation.
 */
public final class RawSlot {
    public enum Severity { INFO, WARN, ERROR }

    private final Instant ts;
    private final String code;      // short machine tag (e.g., E_CANNOT_FIND_SYMBOL)
    private final String message;   // original line
    private final Severity severity;

    public RawSlot(Instant ts, String code, String message, Severity severity) {
        this.ts = Objects.requireNonNull(ts);
        this.code = Objects.requireNonNull(code);
        this.message = Objects.requireNonNull(message);
        this.severity = Objects.requireNonNull(severity);
    }

    public Instant ts() { return ts; }
    public String code() { return code; }
    public String message() { return message; }
    public Severity severity() { return severity; }

    @Override public String toString() {
        return "[" + ts + "] " + code + " (" + severity + "): " + message;
    }

    @Override public int hashCode() { return Objects.hash(ts, code, message, severity); }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RawSlot)) return false;
        RawSlot that = (RawSlot) o;
        return ts.equals(that.ts) && code.equals(that.code) && message.equals(that.message) && severity == that.severity;
    }
}
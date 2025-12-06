package com.example.lms.cfvm.stable;

import java.time.Instant;
import java.util.Objects;

public final class RawSlot {
    public enum Severity { INFO, WARN, ERROR }
    public final Instant ts;
    public final String code;
    public final String message;
    public final Severity severity;

    public RawSlot(Instant ts, String code, String message, Severity severity){
        this.ts = ts; this.code = code; this.message = message; this.severity = severity;
    }

    @Override public boolean equals(Object o){
        if(this==o) return true;
        if(!(o instanceof RawSlot)) return false;
        RawSlot r = (RawSlot)o;
        return Objects.equals(ts, r.ts) && Objects.equals(code, r.code) && Objects.equals(message, r.message) && severity==r.severity;
    }
    @Override public int hashCode(){ return Objects.hash(ts, code, message, severity); }
}
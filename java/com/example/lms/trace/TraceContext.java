// src/main/java/com/example/lms/trace/TraceContext.java
package com.example.lms.trace;

import org.slf4j.MDC;
import java.util.UUID;

/**
 * Utility for attaching tracing identifiers to the current thread context.
 *
 * <p>The {@link com.example.lms.web.TraceFilter} populates the MDC with a
 * session identifier (sid) and a per‑request trace identifier at the
 * beginning of each HTTP request.  For internal calls that are not
 * initiated through the web layer, this helper can be used to
 * temporarily attach custom identifiers onto the MDC.  When the
 * returned {@code TraceContext} is closed, the previous MDC values
 * are restored to avoid leaking identifiers across threads.</p>
 */
public final class TraceContext implements AutoCloseable {
    private final String prevTrace;
    private final String prevSid;

    private TraceContext(String sid, String trace) {
        this.prevSid = MDC.get("sid");
        this.prevTrace = MDC.get("trace");
        if (sid != null) {
            MDC.put("sid", sid);
        }
        MDC.put("trace", trace != null ? trace : UUID.randomUUID().toString());
    }

    /**
     * Attach the given session and trace identifiers to the MDC.  If the
     * provided values are {@code null}, the previous values (if any) are
     * left unchanged.  When the returned context is closed, the MDC is
     * restored to its prior state.
     *
     * @param sid   the session identifier, may be {@code null}
     * @param trace the trace identifier, may be {@code null}
     * @return a context handle that restores the MDC on close
     */
    public static TraceContext attach(String sid, String trace) {
        return new TraceContext(sid, trace);
    }

    @Override
    public void close() {
        if (prevSid != null) {
            MDC.put("sid", prevSid);
        } else {
            MDC.remove("sid");
        }
        if (prevTrace != null) {
            MDC.put("trace", prevTrace);
        } else {
            MDC.remove("trace");
        }
    }
}
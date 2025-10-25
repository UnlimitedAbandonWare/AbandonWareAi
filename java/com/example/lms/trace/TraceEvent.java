// src/main/java/com/example/lms/trace/TraceEvent.java
package com.example.lms.trace;

import java.time.Instant;
import java.util.Map;



/**
 * Immutable record representing a single trace event.  Each event is
 * serialised as a single line of NDJSON via the {@link TraceLogger}.
 *
 * <p>The fields mirror the proposed schema: a timestamp, a type
 * describing what kind of event occurred (e.g. search_decision,
 * retrieval, prompt, llm_call), a pipeline stage, and a set of
 * arbitrary key/value pairs conveying summary metrics or previews.  The
 * session and trace identifiers are copied from the MDC at the time
 * the event is created.</p>
 *
 * @param ts    the timestamp of the event (UTC)
 * @param type  the event type identifier
 * @param stage the stage of the pipeline (search, prompt, llm, post, summary)
 * @param sid   session identifier (may be null)
 * @param trace trace identifier (not null)
 * @param kv    arbitrary structured properties for the event
 */
public record TraceEvent(
        Instant ts,
        String type,
        String stage,
        String sid,
        String trace,
        Map<String, Object> kv
) {
}
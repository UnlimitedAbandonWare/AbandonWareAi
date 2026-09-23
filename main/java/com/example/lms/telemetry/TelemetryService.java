package com.example.lms.telemetry;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


/**
 * Simple telemetry service that records search events.  This component is
 * intentionally lightweight and does not perform any network I/O by default.
 * Applications may override this bean to forward events to external
 * monitoring systems or analytics pipelines.  When the service is not
 * present (e.g. in legacy deployments) calls to {@link #recordSearchEvent}
 * are silently ignored.
 */
@Component
public class TelemetryService {
    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    /**
     * Record a search event with various parameters.  This method is
     * fail-soft: any internal exceptions are caught and suppressed.
     *
     * @param queryText    the original user query
     * @param topK         the dynamic topK used for retrieval
     * @param minRel       the dynamic minimum relatedness threshold
     * @param fused        the number of fused documents returned
     * @param success      whether any results were found
     */
    public void recordSearchEvent(String queryText, int topK, double minRel, int fused, boolean success) {
        try {
            // A simple log entry serves as the default implementation.  Structured
            // metrics can be derived by tailing application logs.  Override this
            // method to integrate with your own telemetry system.
            if (log.isDebugEnabled()) {
                log.debug("[Telemetry] queryHash={}, queryLength={}, topK={}, minRel={}, fused={}, success={}",
                        SafeRedactor.hashValue(queryText),
                        queryText == null ? 0 : queryText.length(),
                        topK, minRel, fused, success);
            }
        } catch (Exception ignore) {
            TraceStore.put("telemetry.searchEvent.suppressed.stage", "record");
            TraceStore.put("telemetry.searchEvent.suppressed.errorType", "telemetry_record_failed");
            TraceStore.put("telemetry.searchEvent.suppressed.record", true);
            TraceStore.put("telemetry.searchEvent.suppressed.record.errorType", "telemetry_record_failed");
            // swallow any logging errors
        }
    }
}

package telemetry;

import com.example.lms.trace.SafeRedactor;

/**
 * Minimal SSE publisher that logs core events.
 */
public class LoggingSseEventPublisher {
    private static final System.Logger LOG = System.getLogger(LoggingSseEventPublisher.class.getName());

    public void emit(String type, String value) {
        LOG.log(System.Logger.Level.DEBUG, "sse.event type={0} valueHash={1} valueLength={2}",
                SafeRedactor.traceLabelOrFallback(type, "unknown"),
                SafeRedactor.hashValue(value),
                value == null ? 0 : value.length());
    }
}

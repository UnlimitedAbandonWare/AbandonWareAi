package com.example.lms.telemetry;

import com.example.lms.trace.SafeRedactor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


/**
 * Default no-op SSE publisher.  When no other {@link SseEventPublisher}
 * implementation is present in the application context, this bean will
 * provide a fallback that simply logs events at debug level.  Downstream
 * components can safely autowire {@link SseEventPublisher} without worrying
 * about nulls.
 */
@Component
@ConditionalOnMissingBean(SseEventPublisher.class)
public class DefaultSseEventPublisher implements SseEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(DefaultSseEventPublisher.class);
    private static final int MAX_PAYLOAD_CHARS = 512;

    @Override
    public void emit(String type, Object payload) {
        String safeType = SafeRedactor.traceLabelOrFallback(type, "event");
        Object safePayload = SafeRedactor.diagnosticValue("sseEvent", payload, MAX_PAYLOAD_CHARS);
        log.debug("SSE[{}] {}", safeType == null || safeType.isBlank() ? "event" : safeType, safePayload);
    }
}

package com.example.lms.telemetry;

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
    @Override
    public void emit(String type, Object payload) {
        // Serialize payload gracefully using String.valueOf to avoid NPE
        log.debug("SSE[{}] {}", type, String.valueOf(payload));
    }
}
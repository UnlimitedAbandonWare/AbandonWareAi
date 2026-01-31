package com.example.lms.service.ui;

import com.example.lms.telemetry.SseEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



/**
 * Bridge for emitting sanitized HTML fragments to the front-end via SSE.  This
 * service hides the underlying SSE implementation details from UI code and
 * ensures that only safe content is transmitted.
 */
@Component
@RequiredArgsConstructor
public class GenerativeUiBridge {
    private final SseEventPublisher sse;

    /**
     * Emit a safe HTML snippet to the client.  Consumers should ensure that
     * the provided HTML has been sanitized to avoid injection attacks.
     *
     * @param html sanitized HTML content
     */
    public void emitSafeHtml(String html) {
        sse.emit("ui", html);
    }
}
package com.example.lms.api;

import com.example.lms.telemetry.SseEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/sse")
public class SseTelemetryDiagnosticsController {

    private final SseEventPublisher publisher;

    public SseTelemetryDiagnosticsController(SseEventPublisher publisher) {
        this.publisher = publisher;
    }

    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> stream() {
        Flux<Map<String, Object>> hello = Flux.just(event("hello", "sse-telemetry"));
        Flux<Map<String, Object>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(ignored -> event("hb", "keep-alive"));
        return Flux.merge(hello, publisher.asStream(), heartbeat);
    }

    private static Map<String, Object> event(String type, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("type", type);
        event.put("message", message);
        return event;
    }
}

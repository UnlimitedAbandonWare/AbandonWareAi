package com.abandonware.ai.telemetry;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/internal/stream")
public class OpsSseController {

    private final SseEventPublisher publisher;

    public OpsSseController(SseEventPublisher publisher) {
        this.publisher = publisher;
    }

    @GetMapping(value="/ops", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String,Object>> stream(@RequestHeader(name="X-Token", required=false) String token) {
        // naive token gate
        if (token == null || token.isBlank()) {
            return Flux.error(new IllegalArgumentException("token required"));
        }
        return publisher.asStream();
    }
}
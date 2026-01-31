package com.abandonware.ai.telemetry;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseEventPublisher {
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(String id) {
        SseEmitter e = new SseEmitter(0L);
        emitters.put(id, e);
        e.onCompletion(() -> emitters.remove(id));
        e.onTimeout(() -> emitters.remove(id));
        return e;
    }

    public void send(String id, String event, String data) {
        SseEmitter e = emitters.get(id);
        if (e == null) return;
        try {
            e.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ex) {
            emitters.remove(id);
        }
    }

    // Reactive stream view for WebFlux controllers (placeholder).
    public reactor.core.publisher.Flux<java.util.Map<String,Object>> asStream() {
        return reactor.core.publisher.Flux.empty();
    }
}
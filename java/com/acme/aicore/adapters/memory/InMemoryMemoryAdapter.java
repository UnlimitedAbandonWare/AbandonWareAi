package com.acme.aicore.adapters.memory;

import com.acme.aicore.domain.model.Message;
import com.acme.aicore.domain.ports.MemoryPort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;




/**
 * Simple in-memory memory implementation suitable for development and testing.
 * Conversations are stored in a {@link ConcurrentHashMap} keyed by sessionId.
 * No eviction policy is applied; see {@link com.acme.aicore.app.SessionManager}
 * for session lifecycle hints.
 */
@Component
public class InMemoryMemoryAdapter implements MemoryPort {
    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();

    @Override
    public Mono<List<Message>> history(String sessionId) {
        return Mono.fromSupplier(() -> store.getOrDefault(sessionId, List.of()));
    }

    @Override
    public Mono<Void> append(String sessionId, Message message) {
        return Mono.fromRunnable(() -> store.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message));
    }

    @Override
    public Mono<Void> clear(String sessionId) {
        return Mono.fromRunnable(() -> store.remove(sessionId));
    }
}
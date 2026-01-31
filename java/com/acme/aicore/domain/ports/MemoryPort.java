package com.acme.aicore.domain.ports;

import com.acme.aicore.domain.model.Message;
import reactor.core.publisher.Mono;
import java.util.List;




/**
 * Interface for chat memory backends.  Allows retrieval and updating of
 * conversation history for a given session ID.  Implementations may store
 * history in memory, Redis, a database or any other persistent store.
 */
public interface MemoryPort {
    Mono<List<Message>> history(String sessionId);
    Mono<Void> append(String sessionId, Message message);
    Mono<Void> clear(String sessionId);
}
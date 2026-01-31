package com.acme.aicore.domain.ports;

import com.acme.aicore.domain.model.GenerationParams;
import com.acme.aicore.domain.model.Prompt;
import com.acme.aicore.domain.model.TokenChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;



/**
 * Abstraction over chat models such as OpenAI’s ChatGPT.  Supports both
 * streaming and non-streaming invocations.  Streaming methods return a
 * Flux of {@link TokenChunk}s which can be transformed into
 * {@link com.acme.aicore.domain.model.AnswerChunk}s by the caller.
 */
public interface ChatModelPort {
    Flux<TokenChunk> stream(Prompt prompt, GenerationParams params);
    Mono<String> complete(Prompt prompt, GenerationParams params);
}
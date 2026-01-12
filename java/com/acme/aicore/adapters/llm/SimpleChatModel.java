package com.acme.aicore.adapters.llm;

import com.acme.aicore.domain.model.GenerationParams;
import com.acme.aicore.domain.model.Prompt;
import com.acme.aicore.domain.model.TokenChunk;
import com.acme.aicore.domain.ports.ChatModelPort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;



/**
 * A trivial chat model adapter that echoes a canned response.  Useful for
 * integration testing and development when an actual LLM is unavailable.
 */
@Component
public class SimpleChatModel implements ChatModelPort {
    @Override
    public Flux<TokenChunk> stream(Prompt prompt, GenerationParams params) {
        String response = "모형 응답.";
        return Flux.just(TokenChunk.of(response));
    }

    @Override
    public Mono<String> complete(Prompt prompt, GenerationParams params) {
        return Mono.just("모형 응답.");
    }
}